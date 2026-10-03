package dev.dalzate.portafolio.controller;

import dev.dalzate.portafolio.model.ContactRequest;
import dev.dalzate.portafolio.service.ContactService;
import dev.dalzate.portafolio.service.RateLimiterService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/contact")
public class ContactController {

    // Un formulario llenado por un humano tarda al menos esto en enviarse;
    // los bots suelen rellenar y mandar el POST casi al instante.
    private static final long MIN_ELAPSED_MS = 1500;

    private final ContactService contactService;
    private final RateLimiterService rateLimiter;

    public ContactController(ContactService contactService, RateLimiterService rateLimiter) {
        this.contactService = contactService;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> contact(
            @Valid @RequestBody ContactRequest req,
            HttpServletRequest httpReq) {

        String ip = resolveClientIp(httpReq);
        if (!rateLimiter.isAllowed(ip)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "Demasiadas solicitudes. Intenta de nuevo en unos minutos."));
        }

        if (looksLikeBot(req)) {
            // Respuesta de éxito falsa: no delatamos la trampa ante el bot,
            // simplemente no se envía el correo de verdad.
            return ResponseEntity.ok(Map.of("status", "sent"));
        }

        contactService.send(req);
        return ResponseEntity.ok(Map.of("status", "sent"));
    }

    private boolean looksLikeBot(ContactRequest req) {
        boolean honeypotFilled = req.website() != null && !req.website().isBlank();
        boolean tooFast = req.elapsedMs() != null && req.elapsedMs() < MIN_ELAPSED_MS;
        return honeypotFilled || tooFast;
    }

    /**
     * El primer valor de X-Forwarded-For lo puede inventar el propio cliente;
     * el proxy de confianza (Render) añade el IP real al final de la lista,
     * así que se usa el último valor en vez del primero.
     */
    private String resolveClientIp(HttpServletRequest req) {
        String forwarded = req.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String[] parts = forwarded.split(",");
            return parts[parts.length - 1].trim();
        }
        return req.getRemoteAddr();
    }
}
