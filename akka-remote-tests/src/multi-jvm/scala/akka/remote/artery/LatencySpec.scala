/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.locks.LockSupport
import scala.concurrent.duration._
import akka.actor._
import akka.remote.testconductor.RoleName
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.remote.testkit.STMultiNodeSpec
import akka.stream.ActorMaterializer
import akka.testkit._
import com.typesafe.config.ConfigFactory
import org.HdrHistogram.Histogram
import akka.stream.scaladsl.Source
import akka.stream.ThrottleMode

object LatencySpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")

  val barrierTimeout = 5.minutes

  commonConfig(debugConfig(on = false).withFallback(
    ConfigFactory.parseString(s"""
       # for serious measurements you should increase the totalMessagesFactor (30) and repeatCount (3)
       akka.test.LatencySpec.totalMessagesFactor = 1.0
       akka.test.LatencySpec.repeatCount = 1
       akka {
         loglevel = ERROR
         # avoid TestEventListener
         loggers = ["akka.event.Logging$$DefaultLogger"]
         testconductor.barrier-timeout = ${barrierTimeout.toSeconds}s
         actor {
           provider = remote
           serialize-creators = false
           serialize-messages = false
         }
         remote.artery {
           enabled = on
           advanced.idle-cpu-level=7

           advanced.compression {
             actor-refs.advertisement-interval = 2 second
             manifests.advertisement-interval = 2 second
           }
         }
       }
       """)))

  final case object Reset

  def echoProps(): Props =
    Props(new Echo)

  class Echo extends Actor {
    // FIXME to avoid using new RemoteActorRef each time
    var cachedSender: ActorRef = null

    def receive = {
      case Reset ⇒
        cachedSender = null
        sender() ! Reset
      case msg ⇒
        if (cachedSender == null) cachedSender = sender()
        cachedSender ! msg
    }
  }

  def receiverProps(reporter: RateReporter, settings: TestSettings, totalMessages: Int,
                    sendTimes: AtomicLongArray, histogram: Histogram, plotsRef: ActorRef): Props =
    Props(new Receiver(reporter, settings, totalMessages, sendTimes, histogram, plotsRef))

  class Receiver(reporter: RateReporter, settings: TestSettings, totalMessages: Int,
                 sendTimes: AtomicLongArray, histogram: Histogram, plotsRef: ActorRef) extends Actor {
    import settings._

    var count = 0
    var startTime = System.nanoTime()
    val taskRunnerMetrics = new TaskRunnerMetrics(context.system)

    def receive = {
      case bytes: Array[Byte] ⇒
        if (bytes.length != 0) {
          if (count == 0)
            startTime = System.nanoTime()
          if (bytes.length != payloadSize) throw new IllegalArgumentException("Invalid message")
          reporter.onMessage(1, payloadSize)
          count += 1
          val d = System.nanoTime() - sendTimes.get(count - 1)
          histogram.recordValue(d)
          if (count == totalMessages) {
            printTotal(testName, bytes.length, histogram, System.nanoTime() - startTime)
            context.stop(self)
          }
        }
    }

    def printTotal(testName: String, payloadSize: Long, histogram: Histogram, totalDurationNanos: Long): Unit = {
      import scala.collection.JavaConverters._
      val percentiles = histogram.percentiles(5)
      def percentile(p: Double): Double =
        percentiles.iterator().asScala.collectFirst {
          case value if (p - 0.5) < value.getPercentileLevelIteratedTo &&
            value.getPercentileLevelIteratedTo < (p + 0.5) ⇒ value.getValueIteratedTo / 1000.0
        }.getOrElse(Double.NaN)

      val throughput = 1000.0 * histogram.getTotalCount / math.max(1, totalDurationNanos.nanos.toMillis)

      println(s"=== Latency $testName: RTT " +
        f"50%%ile: ${percentile(50.0)}%.0f µs, " +
        f"90%%ile: ${percentile(90.0)}%.0f µs, " +
        f"99%%ile: ${percentile(99.0)}%.0f µs, " +
        f"rate: ${throughput}%,.0f msg/s")
      println("Histogram of RTT latencies in microseconds.")
      histogram.outputPercentileDistribution(System.out, 1000.0)

      taskRunnerMetrics.printHistograms()

      val plots = LatencyPlots(
        PlotResult().add(testName, percentile(50.0)),
        PlotResult().add(testName, percentile(90.0)),
        PlotResult().add(testName, percentile(99.0)))
      plotsRef ! plots
    }
  }

  final case class TestSettings(
    testName:    String,
    messageRate: Int, // msg/s
    payloadSize: Int,
    repeat:      Int)

}

class LatencySpecMultiJvmNode1 extends LatencySpec
class LatencySpecMultiJvmNode2 extends LatencySpec

abstract class LatencySpec
  extends MultiNodeSpec(LatencySpec)
  with STMultiNodeSpec with ImplicitSender {

  import LatencySpec._

  val totalMessagesFactor = system.settings.config.getDouble("akka.test.LatencySpec.totalMessagesFactor")
  val repeatCount = system.settings.config.getInt("akka.test.LatencySpec.repeatCount")

  var plots = LatencyPlots()

  lazy implicit val mat = ActorMaterializer()(system)
  import system.dispatcher

  override def initialParticipants = roles.size

  lazy val reporterExecutor = Executors.newFixedThreadPool(1)
  def reporter(name: String): TestRateReporter = {
    val r = new TestRateReporter(name)
    reporterExecutor.execute(r)
    r
  }

  override def afterAll(): Unit = {
    reporterExecutor.shutdown()
    runOn(first) {
      println(plots.plot50.csv(system.name + "50"))
      println(plots.plot90.csv(system.name + "90"))
      println(plots.plot99.csv(system.name + "99"))
    }
    super.afterAll()
  }

  def identifyEcho(name: String = "echo", r: RoleName = second): ActorRef = {
    system.actorSelection(node(r) / "user" / name) ! Identify(None)
    expectMsgType[ActorIdentity].ref.get
  }

  val scenarios = List(
    TestSettings(
      testName = "warmup",
      messageRate = 10000,
      payloadSize = 100,
      repeat = repeatCount),
    TestSettings(
      testName = "rate-100-size-100",
      messageRate = 100,
      payloadSize = 100,
      repeat = repeatCount),
    TestSettings(
      testName = "rate-1000-size-100",
      messageRate = 1000,
      payloadSize = 100,
      repeat = repeatCount),
    TestSettings(
      testName = "rate-10000-size-100",
      messageRate = 10000,
      payloadSize = 100,
      repeat = repeatCount),
    TestSettings(
      testName = "rate-20000-size-100",
      messageRate = 20000,
      payloadSize = 100,
      repeat = repeatCount),
    TestSettings(
      testName = "rate-1000-size-1k",
      messageRate = 1000,
      payloadSize = 1000,
      repeat = repeatCount))

  def test(testSettings: TestSettings): Unit = {
    import testSettings._

    runOn(first) {
      val payload = ("0" * payloadSize).getBytes("utf-8")
      // by default run for 2 seconds, but can be adjusted with the totalMessagesFactor
      val totalMessages = (2 * messageRate * totalMessagesFactor).toInt
      val sendTimes = new AtomicLongArray(totalMessages)
      val histogram = new Histogram(SECONDS.toNanos(10), 3)
      val rep = reporter(testName)

      val echo = identifyEcho()
      val plotProbe = TestProbe()

      // increase the rate somewhat to compensate for overhead, based on heuristics
      // will also be adjusted based on measurement when using > 1 repeat
      @volatile var adjustRateFactor =
        if (messageRate <= 100) 1.05
        else if (messageRate <= 1000) 1.1
        else if (messageRate <= 10000) 1.2
        else if (messageRate <= 20000) 1.3
        else 1.4

      for (n ← 1 to repeat) {
        echo ! Reset
        expectMsg(Reset)
        histogram.reset()
        val receiver = system.actorOf(receiverProps(rep, testSettings, totalMessages, sendTimes, histogram, plotProbe.ref))

        // warmup for 3 seconds to init compression
        val warmup = Source(1 to 30)
          .throttle(10, 1.second, 10, ThrottleMode.Shaping)
          .runForeach { n ⇒
            echo.tell(Array.emptyByteArray, receiver)
          }

        warmup.foreach { _ ⇒
          var i = 0
          var adjust = 0L
          val targetDelay = (SECONDS.toNanos(1) / (messageRate * adjustRateFactor)).toLong
          while (i < totalMessages) {
            LockSupport.parkNanos(targetDelay - adjust)
            val now = System.nanoTime()
            sendTimes.set(i, now)
            if (i >= 1) {
              val diff = now - sendTimes.get(i - 1)
              adjust = math.max(0L, (diff - targetDelay) / 2)
            }

            echo.tell(payload, receiver)
            i += 1
          }

          // measure rate and adjust for next repeat round
          val d = (sendTimes.get(totalMessages - 1) - sendTimes.get(0))
          val measuredRate = totalMessages * SECONDS.toNanos(1) / math.max(1, d)
          val previousTargetRate = messageRate * adjustRateFactor
          adjustRateFactor = (previousTargetRate / math.max(1, measuredRate))
          println(s"Measured send rate $measuredRate msg/s (new adjustment facor: $adjustRateFactor)")
        }

        watch(receiver)
        expectTerminated(receiver, ((totalMessages / messageRate) + 20).seconds)
        val p = plotProbe.expectMsgType[LatencyPlots]
        // only use the last repeat for the plots
        if (n == repeat) {
          plots = plots.copy(
            plot50 = plots.plot50.addAll(p.plot50),
            plot90 = plots.plot90.addAll(p.plot90),
            plot99 = plots.plot99.addAll(p.plot99))
        }
      }

      rep.halt()
    }

    enterBarrier("after-" + testName)
  }

  "Latency of Artery" must {

    "start echo" in {
      runOn(second) {
        // just echo back
        system.actorOf(echoProps, "echo")
      }
      enterBarrier("echo-started")
    }

    for (s ← scenarios) {
      s"be low for ${s.testName}, at ${s.messageRate} msg/s, payloadSize = ${s.payloadSize}" in test(s)
    }

    // TODO add more tests

  }
}