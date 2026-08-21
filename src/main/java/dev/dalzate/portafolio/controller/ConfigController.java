package dev.dalzate.portafolio.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/config")
public class ConfigController {

    @Value("${app.contact.whatsapp}")
    private String whatsapp;

    @Value("${app.contact.email-display}")
    private String emailDisplay;

    @GetMapping
    public Map<String, String> contactConfig() {
        return Map.of(
            "whatsappLink", "https://wa.me/" + whatsapp,
            "emailLink",    "mailto:" + emailDisplay,
            "emailDisplay", emailDisplay
        );
    }
}
