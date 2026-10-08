import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class HealthProbe {
  public static void main(String[] args) {
    try {
      var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
      var request = HttpRequest.newBuilder(URI.create(args[0])).timeout(Duration.ofSeconds(3)).GET().build();
      System.exit(client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200 ? 0 : 1);
    } catch (Exception unavailable) { System.exit(1); }
  }
}
