package dev.dalzate.portafolio.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ContactRequest(
    // Sin \r\n: este campo va directo al Subject del correo y un salto de
    // línea ahí abriría la puerta a inyección de cabeceras de correo.
    @NotBlank @Size(max = 100) @Pattern(regexp = "^[^\\r\\n]*$") String name,
    @NotBlank @Email String email,
    @NotBlank @Size(max = 2000) String message
) {}
