package com.lifeos.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LifeOsDesktopApplicationTest {

    @Test
    void applicationHasStableEntryPoint() {
        assertEquals("com.lifeos.desktop.LifeOsDesktopApplication", LifeOsDesktopApplication.class.getName());
    }

    @Test
    void extractsOnlyTheBearerTokenField() {
        assertEquals("signed-token", LifeOsDesktopApplication.extractAccessToken(
                "{\"accessToken\":\"signed-token\",\"refreshToken\":\"not-used\"}"));
    }
}
