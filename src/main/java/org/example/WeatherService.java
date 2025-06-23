package org.example;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.example.dtos.GeocodingResponse;
import org.example.dtos.GeocodingResult;
import org.example.dtos.WeatherApiResponse;
import org.knowm.xchart.BitmapEncoder;
import org.knowm.xchart.XYChart;
import org.knowm.xchart.XYChartBuilder;
import org.knowm.xchart.style.Styler;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class WeatherService {

    private static final int CACHE_EXPIRATION_SECONDS = 15 * 60; // 15 минут
    private static final Gson gson = new Gson();
    private static final HttpClient httpClient = HttpClient.newHttpClient();
    private static final JedisPool jedisPool = new JedisPool("localhost", 6379);


    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/weather", WeatherService::handleRequest);
        server.setExecutor(null);
        server.start();
        System.out.println("Сервер (Java 17) запущен на порту 8080...");
    }

    private static void handleRequest(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "Метод не поддерживается".getBytes());
            return;
        }

        Map<String, String> params = queryToMap(exchange.getRequestURI().getQuery());
        String city = params.get("city");

        if (city == null || city.trim().isEmpty()) {
            sendResponse(exchange, 400, "Параметр 'city' не указан".getBytes());
            return;
        }

        try {
            String weatherDataJson = getWeatherData(city);
            if (weatherDataJson == null) {
                sendResponse(exchange, 404, ("Город '" + city + "' не найден").getBytes());
                return;
            }

            WeatherApiResponse weatherResponse = gson.fromJson(weatherDataJson, WeatherApiResponse.class);

            byte[] chartImage = createWeatherChart(weatherResponse, city);

            exchange.getResponseHeaders().set("Content-Type", "image/png");
            sendResponse(exchange, 200, chartImage);

        } catch (Exception e) {
            e.printStackTrace();
            sendResponse(exchange, 500, "Внутренняя ошибка сервера".getBytes());
        }
    }

    private static String getWeatherData(String city) throws IOException, InterruptedException {
        String cacheKey = "weather:" + city.toLowerCase();

        try (Jedis jedis = jedisPool.getResource()) {
            String cachedData = jedis.get(cacheKey);
            if (cachedData != null) {
                System.out.println("Данные для города '" + city + "' найдены в кэше.");
                return cachedData;
            }

            System.out.println("Данные для города '" + city + "' не найдены в кэше. Запрос к API...");
            GeocodingResponse geocoding = getCityCoordinates(city);
            if (geocoding == null || geocoding.results() == null || geocoding.results().isEmpty()) {
                return null;
            }

            GeocodingResult cityInfo = geocoding.results().get(0);

            String weatherForecastJson = getForecast(cityInfo.latitude(), cityInfo.longitude());

            jedis.setex(cacheKey, CACHE_EXPIRATION_SECONDS, weatherForecastJson);

            return weatherForecastJson;
        }
    }

    private static GeocodingResponse getCityCoordinates(String city) throws IOException, InterruptedException {
        String url = String.format("https://geocoding-api.open-meteo.com/v1/search?name=%s&count=1", city);
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        return gson.fromJson(response.body(), GeocodingResponse.class);
    }

    private static String getForecast(double lat, double lon) throws IOException, InterruptedException {
        String url = String.format(
                "https://api.open-meteo.com/v1/forecast?latitude=%f&longitude=%f&hourly=temperature_2m", lat, lon);
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return response.body();
    }

    private static byte[] createWeatherChart(WeatherApiResponse weatherData, String city) throws IOException {
        List<LocalDateTime> timePoints = weatherData.hourly().time().stream()
                .map(t -> LocalDateTime.parse(t, DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                .collect(Collectors.toList());

        List<Double> temperatures = weatherData.hourly().temperature_2m();

        List<String> hours = timePoints.subList(0, 24).stream()
                .map(t -> String.format("%02d:00", t.getHour()))
                .collect(Collectors.toList());
        List<Double> tempsForChart = temperatures.subList(0, 24);

        XYChart chart = new XYChartBuilder()
                .width(800)
                .height(600)
                .title("Прогноз погоды для: " + city)
                .xAxisTitle("Время (24 часа)")
                .yAxisTitle("Температура (°C)")
                .build();

        chart.getStyler().setLegendPosition(Styler.LegendPosition.InsideNW);
        chart.getStyler().setXAxisLabelRotation(45);
        chart.getStyler().setMarkerSize(5);

        chart.addSeries("Температура", null, tempsForChart);

        chart.setXAxisTitle("Время");
        chart.getStyler().setxAxisTickLabelsFormattingFunction(x -> hours.get(x.intValue()));

        return BitmapEncoder.getBitmapBytes(chart, BitmapEncoder.BitmapFormat.PNG);
    }

    private static void sendResponse(HttpExchange exchange, int statusCode, byte[] responseBytes) throws IOException {
        exchange.sendResponseHeaders(statusCode, responseBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBytes);
        }
    }

    private static Map<String, String> queryToMap(String query) {
        Map<String, String> result = new HashMap<>();
        if (query == null) return result;
        for (String param : query.split("&")) {
            String[] entry = param.split("=");
            if (entry.length > 1) {
                result.put(entry[0], entry[1]);
            } else {
                result.put(entry[0], "");
            }
        }
        return result;
    }
}