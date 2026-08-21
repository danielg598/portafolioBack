package dev.dalzate.portafolio.service;

import dev.dalzate.portafolio.model.ContactRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class ContactService {

    private final JavaMailSender mailSender;

    @Value("${app.contact.recipient}")
    private String recipient;

    public ContactService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    public void send(ContactRequest req) {
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setTo(recipient);
        msg.setReplyTo(req.email());
        msg.setSubject("Portafolio — mensaje de " + req.name());
        msg.setText("Nombre: " + req.name() + "\nEmail: " + req.email() + "\n\n" + req.message());
        mailSender.send(msg);
    }
}
