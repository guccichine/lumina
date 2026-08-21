package app.lumina;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

final class Classifier {
    static final String[] TYPES = {
            "people", "animals", "nature", "food", "vehicles",
            "screenshots", "documents", "night", "graphics", "other"
    };

    static String label(String type) {
        return switch (type) {
            case "people" -> "People";
            case "animals" -> "Animals";
            case "nature" -> "Nature";
            case "food" -> "Food";
            case "vehicles" -> "Vehicles";
            case "screenshots" -> "Screenshots";
            case "documents" -> "Documents";
            case "night" -> "Night";
            case "graphics" -> "Graphics";
            default -> "Other";
        };
    }

    static Result classify(String filename, String relPath, int width, int height) {
        Map<String, Double> scores = new LinkedHashMap<>();
        for (String t : TYPES) scores.put(t, 0.0);
        String name = filename == null ? "" : filename.toLowerCase(Locale.US);
        String stem = name.replace('_', ' ').replace('-', ' ');
        String folder = folderHint(relPath);
        if (folder != null) scores.put(folder, scores.get(folder) + 5.5);

        add(scores, "people", stem, "portrait", "selfie", "person", "people", "face", "wedding", "hike");
        add(scores, "animals", stem, "dog", "cat", "bird", "puppy", "kitten", "animal", "retriever", "tabby", "pet");
        add(scores, "nature", stem, "landscape", "mountain", "forest", "beach", "lake", "ocean", "sunset", "sunrise", "alpine", "trail", "tree");
        add(scores, "food", stem, "pizza", "ramen", "food", "brunch", "dinner", "pastry", "cake", "sushi", "coffee", "espresso");
        add(scores, "vehicles", stem, "car", "coupe", "truck", "motorcycle", "vehicle", "highway", "sedan");
        add(scores, "screenshots", stem, "screenshot", "screencap", "screengrab", "snipping");
        add(scores, "documents", stem, "receipt", "invoice", "scan", "scanned", "document", "letter");
        add(scores, "night", stem, "night", "nighttime", "neon", "midnight");
        add(scores, "graphics", stem, "meme", "sticker", "comic", "illustration", "logo");

        if (width > 0 && height > width) {
            if ((width == 1080 && height >= 1920) || (width == 1170) || (width == 1284) || name.contains("screenshot")) {
                scores.put("screenshots", scores.get("screenshots") + 3.2);
            }
        }
        String best = "other";
        double bestScore = 0;
        for (Map.Entry<String, Double> e : scores.entrySet()) {
            if (e.getValue() > bestScore) {
                bestScore = e.getValue();
                best = e.getKey();
            }
        }
        if (bestScore < 1.15) best = "other";
        Result r = new Result();
        r.type = best;
        r.confidence = Math.min(0.97, 0.45 + bestScore / 8);
        r.reason = folder != null ? "already in " + label(folder) : "name";
        return r;
    }

    private static void add(Map<String, Double> scores, String type, String stem, String... words) {
        for (String w : words) {
            if (Pattern.compile("\\b" + Pattern.quote(w) + "\\b").matcher(stem).find()) {
                scores.put(type, scores.get(type) + 3.4);
                return;
            }
        }
    }

    static String folderHint(String rel) {
        if (rel == null || !rel.contains("/")) return null;
        String folder = rel.substring(0, rel.indexOf('/')).toLowerCase(Locale.US);
        for (String t : TYPES) {
            if (t.equals(folder) || label(t).toLowerCase(Locale.US).equals(folder)) return t;
        }
        return null;
    }

    static final class Result {
        String type;
        double confidence;
        String reason;
    }
}
