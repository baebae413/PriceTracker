package ru.pricetracker;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

public class PodrygkaParser {
    public static class Result {
        public String name = "";
        public double price = -1;
        public double oldPrice = -1;
    }

    public static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
        c.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8");
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in == null) throw new IOException("HTTP " + code);
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder b = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) b.append(line).append('\n');
            return b.toString();
        } finally {
            c.disconnect();
        }
    }

    public static Result product(String url) throws Exception {
        String html = get(url);
        Result r = new Result();

        // Product title: first H1.
        Matcher h1 = Pattern.compile("<h1[^>]*>(.*?)</h1>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
                .matcher(html);
        if (h1.find()) r.name = clean(h1.group(1));

        // Prefer JSON-LD price if present.
        Matcher json = Pattern.compile(
                "\"price\"\\s*:\\s*\"?([0-9]+(?:[.,][0-9]+)?)\"?",
                Pattern.CASE_INSENSITIVE).matcher(html);
        if (json.find()) r.price = num(json.group(1));

        // Fallback: look around visible price markers.
        if (r.price < 0) {
            Matcher m = Pattern.compile(
                    "(?s)(?:price|цена)[^0-9]{0,300}([0-9]{2,6}(?:[.,][0-9]{1,2})?)\\s*(?:₽|руб)",
                    Pattern.CASE_INSENSITIVE).matcher(html);
            if (m.find()) r.price = num(m.group(1));
        }

        // Podrygka commonly exposes two nearby ruble amounts. If JSON-LD
        // produced one price, find a second visible amount as old price.
        Matcher rub = Pattern.compile("([0-9]{2,6}(?:[.,][0-9]{1,2})?)\\s*₽").matcher(stripTags(html));
        ArrayList<Double> vals = new ArrayList<>();
        while (rub.find() && vals.size() < 20) {
            double v = num(rub.group(1));
            if (v > 0) vals.add(v);
        }
        if (vals.size() >= 2) {
            // Use the larger of the first two plausible values as the old price.
            double a = vals.get(0), b = vals.get(1);
            if (r.price > 0 && a != r.price && b != r.price) r.oldPrice = Math.max(a, b);
            else if (r.price > 0) r.oldPrice = Math.max(a, b);
        }

        if (r.price < 0) throw new IOException("Цена не найдена");
        return r;
    }

    public static List<String> category(String url) throws Exception {
        String html = get(url);
        LinkedHashSet<String> links = new LinkedHashSet<>();
        Pattern p = Pattern.compile("href\\s*=\\s*\"([^\"]*?/catalog/[^\"#?]+)\"",
                Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(html);
        while (m.find()) {
            String x = m.group(1);
            if (x.contains("category_")) continue;
            if (!x.matches(".*?/\\d{4,}-[^/]+/?$")) continue;
            if (x.startsWith("/")) x = "https://www.podrygka.ru" + x;
            links.add(x);
        }
        return new ArrayList<>(links);
    }

    private static double num(String s) {
        return Double.parseDouble(s.replace(" ", "").replace(",", "."));
    }

    private static String clean(String s) {
        return stripTags(s).replace("&nbsp;", " ").replace("&amp;", "&")
                .replaceAll("\\s+", " ").trim();
    }

    private static String stripTags(String s) {
        return s.replaceAll("<[^>]+>", " ").replace("&nbsp;", " ");
    }
}