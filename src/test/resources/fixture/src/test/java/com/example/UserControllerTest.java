package com.example;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class UserControllerTest {
    @Test
    void controllerExists() {
        assertThat(new UserController()).isNotNull();
    }
}
