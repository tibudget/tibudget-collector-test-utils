package com.tibudget.test;

import com.tibudget.api.InternetProvider;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class OkHttpInternetProviderTest {
    @Test
    void get_shouldReturnHttp200AndBody() throws IOException {

        InternetProvider internet = new OkHttpInternetProvider();

        InternetProvider.Response response = internet.get(
                "https://httpbin.org/get",
                Map.of()
        );

        assertNotNull(response);
        assertEquals(200, response.code);
        assertNotNull(response.body);
        assertTrue(response.body.contains("\"url\": \"https://httpbin.org/get\""));
    }
}
