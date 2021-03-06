package mesosphere.marathon
package core.health.impl

import java.net.{InetSocketAddress, Socket}
import java.security.cert.X509Certificate
import javax.net.ssl.{KeyManager, SSLContext, X509TrustManager}

import akka.actor.{Actor, ActorSystem, PoisonPill}
import akka.http.scaladsl.settings.ClientConnectionSettings
import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, headers}
import akka.http.scaladsl.{ConnectionContext, Http}
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import com.typesafe.scalalogging.StrictLogging
import com.typesafe.sslconfig.akka.AkkaSSLConfig
import mesosphere.marathon.core.health._
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.state.{AppDefinition, Timestamp}
import mesosphere.util.ThreadPoolContext

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

class HealthCheckWorkerActor(implicit mat: Materializer) extends Actor with StrictLogging {

  import HealthCheckWorker._

  private implicit val system = context.system
  import context.dispatcher // execution context for futures

  def receive: Receive = {
    case HealthCheckJob(app, instance, check) =>
      val replyTo = sender() // avoids closing over the volatile sender ref

      doCheck(app, instance, check)
        .andThen {
          case Success(Some(result)) => replyTo ! result
          case Success(None) => // ignore
          case Failure(t) =>
            logger.warn(s"Performing health check for app=${app.id} instance=${instance.instanceId} port=${check.port} failed with exception", t)
            replyTo ! Unhealthy(
              instance.instanceId,
              instance.runSpecVersion,
              s"${t.getClass.getSimpleName}: ${t.getMessage}"
            )
        }
        .onComplete { _ => self ! PoisonPill }
  }

  def doCheck(
    app: AppDefinition, instance: Instance, check: MarathonHealthCheck): Future[Option[HealthResult]] = {
    // HealthChecks are only supported for legacy App instances with exactly one task
    val effectiveIpAddress = instance.appTask.status.networkInfo.effectiveIpAddress(app)
    effectiveIpAddress match {
      case Some(host) =>
        val maybePort = check.effectivePort(app, instance)
        (check, maybePort) match {
          case (hc: MarathonHttpHealthCheck, Some(port)) =>
            hc.protocol match {
              case Protos.HealthCheckDefinition.Protocol.HTTPS => https(instance, hc, host, port)
              case Protos.HealthCheckDefinition.Protocol.HTTP => http(instance, hc, host, port)
              case invalidProtocol: Protos.HealthCheckDefinition.Protocol =>
                Future.failed {
                  val message = s"Health check failed: HTTP health check contains invalid protocol: $invalidProtocol"
                  logger.warn(message)
                  new UnsupportedOperationException(message)
                }
            }
          case (hc: MarathonTcpHealthCheck, Some(port)) => tcp(instance, hc, host, port)
          case _ => Future.failed {
            val message = "Health check failed: unable to get the task's effectivePort"
            logger.warn(message)
            new UnsupportedOperationException(message)
          }
        }
      case None =>
        Future.failed {
          val message = "Health check failed: unable to get the task's effective IP address"
          logger.warn(message)
          new UnsupportedOperationException(message)
        }
    }
  }

  def http(
    instance: Instance,
    check: MarathonHttpHealthCheck,
    host: String,
    port: Int): Future[Option[HealthResult]] = {
    val rawPath = check.path.getOrElse("")
    val absolutePath = if (rawPath.startsWith("/")) rawPath else s"/$rawPath"
    val url = s"http://$host:$port$absolutePath"
    logger.debug(s"Checking the health of [$url] for instance=${instance.instanceId} via HTTP")

    singleRequest(
      RequestBuilding.Get(url),
      check.timeout
    ).map { response =>
        response.discardEntityBytes() //forget about the body
        if (acceptableResponses.contains(response.status.intValue())) {
          Some(Healthy(instance.instanceId, instance.runSpecVersion))
        } else if (check.ignoreHttp1xx && (toIgnoreResponses.contains(response.status.intValue))) {
          logger.debug(s"Ignoring health check HTTP response ${response.status.intValue} for instance=${instance.instanceId}")
          None
        } else {
          logger.debug(s"Health check for instance=${instance.instanceId} responded with ${response.status}")
          Some(Unhealthy(instance.instanceId, instance.runSpecVersion, response.status.toString()))
        }
      }.recover {
        case NonFatal(e) =>
          logger.debug(s"Health check for instance=${instance.instanceId} did not respond due to ${e.getMessage}.")
          Some(Unhealthy(instance.instanceId, instance.runSpecVersion, e.getMessage))
      }
  }

  def tcp(
    instance: Instance,
    check: MarathonTcpHealthCheck,
    host: String,
    port: Int): Future[Option[HealthResult]] = {
    val address = s"$host:$port"
    val timeoutMillis = check.timeout.toMillis.toInt
    logger.debug(s"Checking the health of [$address] for instance=${instance.instanceId} via TCP")

    Future {
      val address = new InetSocketAddress(host, port)
      val socket = new Socket
      scala.concurrent.blocking {
        socket.connect(address, timeoutMillis)
        socket.close()
      }
      Some(Healthy(instance.instanceId, instance.runSpecVersion, Timestamp.now()))
    }(ThreadPoolContext.ioContext)
  }

  def https(
    instance: Instance,
    check: MarathonHttpHealthCheck,
    host: String,
    port: Int): Future[Option[HealthResult]] = {

    val rawPath = check.path.getOrElse("")
    val absolutePath = if (rawPath.startsWith("/")) rawPath else s"/$rawPath"
    val url = s"https://$host:$port$absolutePath"
    logger.debug(s"Checking the health of [$url] for instance=${instance.instanceId} via HTTPS")

    singleRequestHttps(
      RequestBuilding.Get(url),
      check.timeout
    ).map { response =>
        response.discardEntityBytes() // forget about the body
        if (acceptableResponses.contains(response.status.intValue())) {
          Some(Healthy(instance.instanceId, instance.runSpecVersion))
        } else {
          logger.debug(s"Health check for ${instance.instanceId} responded with ${response.status}")
          Some(Unhealthy(instance.instanceId, instance.runSpecVersion, response.status.toString()))
        }
      }.recover {
        case NonFatal(e) =>
          logger.debug(s"Health check for instance=${instance.instanceId} did not respond due to ${e.getMessage}.")
          Some(Unhealthy(instance.instanceId, instance.runSpecVersion, e.getMessage))
      }
  }

  def singleRequest(httpRequest: HttpRequest, timeout: FiniteDuration): Future[HttpResponse] = {
    val host = httpRequest.uri.authority.host.toString()
    val port = httpRequest.uri.effectivePort
    val hostHeader = headers.Host(host, port)
    val effectiveRequest = httpRequest
      .withUri(httpRequest.uri.toHttpRequestTargetOriginForm)
      .withDefaultHeaders(hostHeader)

    val connectionFlow = Http().outgoingConnection(
      host,
      port,
      settings = ClientConnectionSettings(system).withIdleTimeout(timeout)
    )
    Source.single(effectiveRequest).via(connectionFlow).runWith(Sink.head)
  }

  def singleRequestHttps(httpRequest: HttpRequest, timeout: FiniteDuration): Future[HttpResponse] = {
    val host = httpRequest.uri.authority.host.toString()
    val port = httpRequest.uri.effectivePort
    val hostHeader = headers.Host(host, port)
    val effectiveRequest = httpRequest
      .withUri(httpRequest.uri.toHttpRequestTargetOriginForm)
      .withDefaultHeaders(hostHeader)
    // This is only a health check, so we are going to allow _very_ bad SSL configuration.
    val connectionFlow = Http().outgoingConnectionHttps(
      host,
      port,
      ConnectionContext.https(disabledSslContext, sslConfig = Some(disabledSslConfig())),
      settings = ClientConnectionSettings(system).withIdleTimeout(timeout)
    )
    Source.single(effectiveRequest).via(connectionFlow).runWith(Sink.head)
  }
}

object HealthCheckWorker {

  // Similar to AWS R53, we accept all responses in [200, 399]
  protected[health] val acceptableResponses = Range(200, 400)
  protected[health] val toIgnoreResponses = Range(100, 200)

  case class HealthCheckJob(app: AppDefinition, instance: Instance, check: MarathonHealthCheck)

  val disabledSslContext: SSLContext = {
    object BlindFaithX509TrustManager extends X509TrustManager {
      def checkClientTrusted(chain: Array[X509Certificate], authType: String): Unit = {}
      def checkServerTrusted(chain: Array[X509Certificate], authType: String): Unit = {}
      def getAcceptedIssuers: Array[X509Certificate] = Array[X509Certificate]()
    }

    val context = SSLContext.getInstance("TLS")
    context.init(Array[KeyManager](), Array(BlindFaithX509TrustManager), null)
    context
  }

  def disabledSslConfig()(implicit as: ActorSystem) = AkkaSSLConfig().mapSettings(s => s.withLoose {
    s.loose.withAcceptAnyCertificate(true)
      .withAllowLegacyHelloMessages(Some(true))
      .withAllowUnsafeRenegotiation(Some(true))
      .withAllowWeakCiphers(true)
      .withAllowWeakProtocols(true)
      .withDisableHostnameVerification(true)
      .withDisableSNI(true)
  })
}
