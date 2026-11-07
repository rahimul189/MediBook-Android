package com.example.medibook.airquality;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.HttpsURLConnection;

/** A real HTTPS GET, used only on the repository executor. No key or device coordinates. */
public final class AirQualityHttpClient {
    public static final String ENDPOINT = "https://air-quality-api.open-meteo.com/v1/air-quality"
            + "?latitude=22.8456&longitude=89.5403&current=us_aqi,pm2_5,pm10,uv_index&timezone=Asia%2FDhaka&timeformat=unixtime";
    public static final int MAX_BYTES = 256 * 1024;
    public interface Response {
        int status() throws IOException;
        InputStream body() throws IOException;
    }
    /** Shared bounded-body and HTTP validation path is also exercised with fake responses in tests. */
    public static String readResponse(Response response) throws IOException {
        if (response.status() != 200) throw new AirQualityException(AirQualityException.Kind.HTTP);
        try (InputStream input = response.body(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) throw new AirQualityException(AirQualityException.Kind.FORMAT);
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > MAX_BYTES) throw new AirQualityException(AirQualityException.Kind.FORMAT);
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }
    public String fetch() throws AirQualityException {
        HttpsURLConnection connection = null;
        try {
            connection = (HttpsURLConnection) new URL(ENDPOINT).openConnection();
            connection.setRequestMethod("GET"); connection.setConnectTimeout(10_000); connection.setReadTimeout(12_000);
            connection.setInstanceFollowRedirects(false); connection.setRequestProperty("Accept", "application/json");
            HttpsURLConnection active = connection;
            return readResponse(new Response() {
                @Override public int status() throws IOException { return active.getResponseCode(); }
                @Override public InputStream body() throws IOException { return active.getInputStream(); }
            });
        } catch (AirQualityException failure) { throw failure; }
        catch (IOException | RuntimeException network) { throw new AirQualityException(AirQualityException.Kind.NETWORK); }
        finally { if (connection != null) connection.disconnect(); }
    }
}
