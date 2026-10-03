package dev.dalzate.portafolio.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dalzate.portafolio.model.ContactRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Envía el correo de contacto vía la API HTTPS de Brevo en vez de SMTP:
 * Render bloquea el tráfico saliente a los puertos SMTP (25/465/587) en
 * el plan free, así que una conexión JavaMail a smtp.gmail.com nunca
 * conecta ahí. La API de Brevo viaja por HTTPS (puerto 443), que no se
 * bloquea.
 */
@Service
public class ContactService {

    private static final URI BREVO_ENDPOINT = URI.create("https://api.brevo.com/v3/smtp/email");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.brevo.api-key}")
    private String apiKey;

    @Value("${app.brevo.sender-email}")
    private String senderEmail;

    @Value("${app.contact.recipient}")
    private String recipient;

    public void send(ContactRequest req) {
        Map<String, Object> payload = Map.of(
                "sender", Map.of("name", "Portafolio", "email", senderEmail),
                "to", List.of(Map.of("email", recipient)),
                "replyTo", Map.of("email", req.email(), "name", req.name()),
                "subject", "Portafolio — mensaje de " + req.name(),
                "textContent", "Nombre: " + req.name() + "\nEmail: " + req.email() + "\n\n" + req.message()
        );

        try {
            HttpRequest httpRequest = HttpRequest.newBuilder(BREVO_ENDPOINT)
                    .timeout(Duration.ofSeconds(10))
                    .header("accept", "application/json")
                    .header("content-type", "application/json")
                    .header("api-key", apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new IllegalStateException("Brevo respondió " + response.statusCode() + ": " + response.body());
            }
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo enviar el mensaje de contacto", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Envío de contacto interrumpido", e);
        }
    }
}
