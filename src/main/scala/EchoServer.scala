
import java.io._
import java.net.{ServerSocket, Socket}
import java.nio.file.{Files, Paths}
import java.util

import FileActor.LoadRequest
import ServerActor.{NewConnection, NotFoundResponse, OkResponse}

import scala.concurrent.Future
import scala.io.BufferedSource
import sun.net.www.http.HttpClient
import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}

object EchoServer {
	def read_and_write(in: BufferedReader, out:BufferedWriter): Unit = {
		out.write(in.readLine())
		out.flush()
		in.close()
		out.close()
	}

	def serve(server: ServerSocket): Unit = {
		val s = server.accept()

		val in = new BufferedReader(new InputStreamReader(s.getInputStream))
		val out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream))

		read_and_write(in, out)

		s.close()
	}

	// Default serve path
	var servePath = "."

	def main(args: Array[String]): Unit = {
		actorMain(args)
		// futureMain(args)
	}

	def actorMain(args: Array[String]): Unit = {
		if (args.length > 0) { servePath = args(0) }

		// using 'Akka', we need to create a system to house all the actors.
		val system: ActorSystem = ActorSystem("AkkaServer")


		val fileActor: ActorRef = system.actorOf(FileActor.props(servePath), "fileActor")
		val serverActor : ActorRef = system.actorOf(ServerActor.props(fileActor), "serverActor")

		System.out.println("user.dir = " + System.getProperty("user.dir"))

		System.out.println("Main: Serving from " + Paths.get(servePath).toAbsolutePath())

		ServerActor.listen(serverActor)
		System.out.println("Program is now serving.");


	}

	def futureMain(args: Array[String]): Unit = {

		if (args.length > 0) { servePath = args(0) }
		System.out.println("user.dir = " + System.getProperty("user.dir"))

		System.out.println("Main: Serving from " + Paths.get(servePath).toAbsolutePath())

		new HttpServer(servePath).serve()
	}

}


/** Class wrapping HTTP Server behavior */
class HttpServer(val path: String) {
	object Info {
		def onWindows(): Boolean = {
			System.getProperties().list(System.out)

			System.getProperty("os.name").toLowerCase().contains("windows");
		}
		val IsWindows = onWindows()
	}
	System.out.println("HttpServer: Serving from " + Paths.get(path).toAbsolutePath())
	var running: Boolean = true;

	def stop(): Unit = {
		running = false
	}
	def serve(): Unit = {
		val server = new ServerSocket(9999)

		while (running) {
			val socket = server.accept()

			System.out.println("Got connection from client.")

			// Asynchronously handle the connection
			Future {
				val request = new HttpRequest(socket)

				handle(request)

				// request.finish()


				System.out.println("actually done now.")
			}
			// Creating the future completes nearly instantly,
			// Allowing the loop to complete, and begin waiting for the next connection.
		}
	}

	/** Simple http request handler */
	def handle(request: HttpRequest): Unit = {

		var requestPath : String = path + request.path
		if (requestPath.endsWith("/")) { requestPath = requestPath + "index.html" }

		System.out.println("Request for file at: " + Paths.get(requestPath).toAbsolutePath)

		// we went a bit further and created a typed future for loading each file on request
		val f : Future[Array[Byte]] = Future {
			Files.readAllBytes(Paths.get(requestPath))
		}
		f onComplete {
			// File Successfully Found
			case Success(bytes) => {
				request.println("HTTP/1.1 200 OK")
				request.println("Content-Type: text/html; charset=utf-8")
				request.println("")
				// If we have a file, respond with the file content
				request.print(bytes)
				request.finish()
			}
			// 404 File not Found
			case Failure(t) => {
				System.out.println(t)

				request.println("HTTP/1.1 404 File not Found")
				request.println("Content-Type: text/html; charset=utf-8")
				request.println("")

				// Otherwise respond with a 404 response
				request.println("404 " + requestPath + " not found.")
				request.finish()
			}
		}


	}
}



/** HttpRequest class. Takes a socket, reads the input, and provides all information about the request. */
class HttpRequest(val socket: Socket) {
	private val source : BufferedSource = new BufferedSource(socket.getInputStream())
	private val in : Iterator[String] = source.getLines()


	private val out : OutputStream = socket.getOutputStream()
	private val pout : PrintStream = new PrintStream(out)
	private val lines : List[String] = readLines(in)
	for (line <- lines) {
		System.out.println("got: " + line)
	}

	private val topLine: Array[String] = lines.head.split(" ")

	/** Http Verb, eg GET, PUT, POST, HEAD */
	val verb: String = topLine(0)
	/** Path of request */
	val path: String = topLine(1)
	/** Http version of request */
	val version: String = topLine(2)

	/** Headers included in request (we don't read these yet) */
	val headers: util.Map[String, List[String]] = new util.HashMap()

	/** Dummy finish method. Replies to request with "hi" */
	def finish(): Unit = {
		pout.flush()
		out.flush()
		socket.close()
	}

	def println(str: String): Unit = {
		pout.println(str)
	}
	def print(str: String): Unit = {
		pout.print(str)
	}
	def print(bytes: Array[Byte]): Unit = {
		out.write(bytes)
	}
	/** Move some nasty imperative code into its own method */
	def readLines(in: Iterator[String]): List[String] = {
		// Flag to break loop
		var go : Boolean = true;
		var lines : List[String] = List()

		while (go) {
			val line: String = in.next()

			if (line.length == 0) {
				// Empty line, stop reading.
				go = false;
			} else {
				// Append line to end of list
				lines = lines :+ line
			}
		}
		// Return value:
		lines
	}
}


object ServerActor {
	def listen(serverActor: ActorRef): Unit = {
		// Do this as a future to not block calling thread.
		Future {
			val server = new ServerSocket(9999)

			while (true) {
				val socket = server.accept()

				System.out.println("Got connection from client.")

				serverActor ! NewConnection(socket)


			}
			// Creating the future completes nearly instantly,
			// Allowing the loop to complete, and begin waiting for the next connection.

		}
	}

	def props(fileActor: ActorRef): Props = Props(new ServerActor(fileActor))

	final case class NewConnection(socket: Socket)

	abstract class Response(request: HttpRequest, bytes: Array[Byte], error: Throwable)
	final case class OkResponse(request: HttpRequest, bytes: Array[Byte], error: Throwable) extends Response(request, bytes, error)
	final case class NotFoundResponse(request: HttpRequest, bytes: Array[Byte], error: Throwable) extends Response(request, bytes, error)


}

class ServerActor(fileActor: ActorRef) extends  Actor {
	def receive = {
		case NewConnection(conn) => {
			System.out.println("newConnection")
			val request = new HttpRequest(conn)
			System.out.println("parsed http Request, requesting " + request.path)
			fileActor ! LoadRequest(request, self)
		}
		case OkResponse(request, bytes, error) => {
			request.println("HTTP/1.1 200 OK")
			request.println("Content-Type: text/html; charset=utf-8")
			request.println("")
			// If we have a file, respond with the file content
			request.print(bytes)
			request.finish()
		}
		case NotFoundResponse(request, bytes, error) => {
			System.out.println(error)

			request.println("HTTP/1.1 404 File not Found")
			request.println("Content-Type: text/html; charset=utf-8")
			request.println("")

			// Otherwise respond with a 404 response
			request.println("404 " + request.path + " not found.")
			request.finish()
		}
	}

}

object FileActor {
	def props(path: String): Props = Props(new FileActor(path))
	final case class LoadRequest(request: HttpRequest, callbackQueue: ActorRef)
}

class FileActor(path: String) extends Actor {
	def receive = {
		case LoadRequest(request , callbackQueue) => {
			System.out.println("Got load request for " + request.path)

			var requestPath : String = path + request.path
			if (requestPath.endsWith("/")) { requestPath = requestPath + "index.html" }

			try {
				val bytes: Array[Byte] = Files.readAllBytes(Paths.get(requestPath))
				callbackQueue ! OkResponse(request, bytes, null)
			} catch {
				case e: Exception => {
					callbackQueue ! NotFoundResponse(request, null, e)
				}
			}



		}
	}
}