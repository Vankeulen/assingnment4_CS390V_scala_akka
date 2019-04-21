import java.io.{BufferedReader, BufferedWriter, ByteArrayInputStream, ByteArrayOutputStream}
import java.net._
import java.util.concurrent.atomic.AtomicReference

import org.mockito.Mockito._
import org.scalatest._
import org.scalatest.mockito.MockitoSugar
import sun.net.www.http.HttpClient
import scala.concurrent.ExecutionContext.Implicits.global


class TestHttpServer extends FlatSpec with Matchers with MockitoSugar {


	"Request" should "be handled" in {
		val server: AtomicReference[HttpServer] = new AtomicReference[HttpServer]()
		def onWindows(): Boolean = {
			System.getProperties().list(System.out)

			System.getProperty("os.name").toLowerCase().contains("windows");
		}

		val thread = new Thread {
			// Run our server in another thread, since it blocks.
			override def run: Unit ={
				server.set(new HttpServer("testfs"))

				server.get().serve()
			}
		}
		thread.start()
		Thread.sleep(1000)

		val socket: Socket = new Socket("127.0.0.1", 9999)

		socket.getOutputStream().write("GET / HTTP/1.1\n\n".getBytes())
		Thread.sleep(1000)

		val data: Array[Byte] = new Array[Byte](1024);
		val number: Int = socket.getInputStream().read(data)

		val body = new String(data, 0, number, "ASCII")

		var expected = "HTTP/1.1 200 OK"
		if (onWindows()) {
			expected += "\r\n\r\n"
		} else {
			expected += "\n\n"
		}
		expected += "<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n\t<meta charset=\"UTF-8\">\n\t<title>Title</title>\n</head>\n<body>\n\thi.\n</body>\n</html>"



		number should be(expected.length)
		body should be(expected)

	}

	"HttpRequest" should "be able to parse the first line" in {
		var out = new ByteArrayOutputStream();
		val in = new ByteArrayInputStream((
			"GET / HTTP/1.1" +
				"\n\n" // End http request with empty line
			).getBytes())

		val socket = mock[Socket]
		when(socket.getInputStream).thenReturn(in)
		when(socket.getOutputStream).thenReturn(out)

		val request: HttpRequest = new HttpRequest(socket)

		request.path should be("/")
		request.verb should be("GET")
		request.version should be("HTTP/1.1")

	}

	"HttpRequest" should "be able to parse stuff" in {
		var out = new ByteArrayOutputStream();
		val in = new ByteArrayInputStream((
			"GET / HTTP/1.1" +
			"\nHost: localhost:9999" +
			"\nConnection: keep-alive" +
			"\nCache-Control: max-age=0" +
			"\nUpgrade-Insecure-Requests: 1" +
			"\nUser-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/72.0.3626.121 Safari/537.36" +
			"\nAccept: text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8" +
			"\nAccept-Encoding: gzip, deflate, br" +
			"\nAccept-Language: en-US,en;q=0.9" +
				"\n\n"// end http request with empty line
			).getBytes())

		val socket = mock[Socket]
		when(socket.getInputStream).thenReturn(in)
		when(socket.getOutputStream).thenReturn(out)

		val request: HttpRequest = new HttpRequest(socket)

		request.path should be("/")
		request.verb should be("GET")
		request.version should be("HTTP/1.1")

		// tbd: count of headers, presence of headers...

	}


}
