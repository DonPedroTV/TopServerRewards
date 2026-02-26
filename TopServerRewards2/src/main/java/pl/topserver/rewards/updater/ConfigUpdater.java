package pl.topserver.rewards.updater;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import pl.topserver.rewards.TopServerRewards;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.logging.Logger;

public class ConfigUpdater {

    private final TopServerRewards plugin;
    private final Logger logger;

    public ConfigUpdater(TopServerRewards plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    public void update() {
        File configFile = new File(plugin.getDataFolder(), "config.yml");

        if (!configFile.exists()) {
            return;
        }

        // Wczytaj domyślny config z JAR jako YAML i jako tekst (raz!)
        List<String> defaultLines = readResourceLines();
        if (defaultLines == null || defaultLines.isEmpty()) {
            logger.warning("[ConfigUpdater] Nie udalo sie wczytac domyslnego config.yml z JAR!");
            return;
        }

        FileConfiguration defaultConfig = loadYamlFromLines(defaultLines);
        if (defaultConfig == null) {
            logger.warning("[ConfigUpdater] Nie udalo sie sparsowac domyslnego configu!");
            return;
        }

        // WAŻNE: NIE używamy plugin.getConfig() bo Bukkit merguje domyślne wartości z
        // JAR!
        // Ładujemy config BEZPOŚREDNIO z pliku, żeby widzieć co NAPRAWDĘ jest w pliku
        // użytkownika
        FileConfiguration userConfig = YamlConfiguration.loadConfiguration(configFile);
        int userVersion = userConfig.getInt("config-version", 0);
        int defaultVersion = defaultConfig.getInt("config-version", 0);

        logger.info("[ConfigUpdater] Wersja uzytkownika: " + userVersion + ", domyslna: " + defaultVersion);

        // Zawsze sprawdzaj brakujące klucze (nawet jeśli wersja się zgadza)
        List<String> missingKeys = findMissingKeys(userConfig, defaultConfig);
        logger.info("[ConfigUpdater] Znaleziono " + missingKeys.size() + " brakujacych kluczy: " + missingKeys);

        if (userVersion >= defaultVersion && missingKeys.isEmpty()) {
            logger.info("[ConfigUpdater] Config jest aktualny, brak zmian.");
            return;
        }

        logger.info("[ConfigUpdater] Aktualizuje config z v" + userVersion + " do v" + defaultVersion + "...");

        try {
            if (missingKeys.isEmpty()) {
                // Tylko zaktualizuj config-version w pliku
                updateConfigVersionInFile(configFile, defaultVersion);
                plugin.reloadConfig();
                return;
            }

            // Wczytaj plik uzytkownika jako tekst
            List<String> userLines = new ArrayList<>(Files.readAllLines(configFile.toPath(), StandardCharsets.UTF_8));
            logger.info("[ConfigUpdater] Wczytano " + userLines.size() + " linii z config.yml uzytkownika");
            logger.info("[ConfigUpdater] Wczytano " + defaultLines.size() + " linii z domyslnego configu");

            // Grupuj brakujące klucze po sekcji top-level
            Map<String, List<String>> keysBySection = new LinkedHashMap<>();
            for (String key : missingKeys) {
                String section = key.contains(".") ? key.substring(0, key.indexOf('.')) : key;
                keysBySection.computeIfAbsent(section, k -> new ArrayList<>()).add(key);
            }

            for (Map.Entry<String, List<String>> entry : keysBySection.entrySet()) {
                String section = entry.getKey();
                List<String> keys = entry.getValue();

                boolean sectionInFile = lineExists(userLines, section + ":");
                logger.info("[ConfigUpdater] Sekcja '" + section + "' istnieje w pliku: " + sectionInFile);

                if (!sectionInFile) {
                    // Cala sekcja brakuje — wyciągnij z domyślnego i dopisz na końcu
                    List<String> sectionText = extractSection(defaultLines, section);
                    logger.info("[ConfigUpdater] Wyciagnieto " + sectionText.size() + " linii dla sekcji '" + section
                            + "'");

                    if (!sectionText.isEmpty()) {
                        userLines.add("");
                        userLines.addAll(sectionText);
                    } else {
                        // Fallback: generuj sekcję ręcznie
                        logger.info("[ConfigUpdater] Fallback: generuje sekcje '" + section + "' recznie");
                        userLines.add("");
                        userLines.add(section + ":");
                        for (String key : keys) {
                            String subKey = key.substring(section.length() + 1);
                            Object val = defaultConfig.get(key);
                            userLines.add("  " + subKey + ": " + toYaml(val));
                        }
                    }
                } else {
                    // Sekcja istnieje — wyciągnij brakujące linie z domyślnego configu
                    int insertAt = findSectionEnd(userLines, section);
                    logger.info("[ConfigUpdater] Wstawiam klucze do sekcji '" + section + "' na pozycji " + insertAt);

                    List<String> newLines = new ArrayList<>();
                    for (String key : keys) {
                        // Spróbuj wyciągnąć linie z domyślnego configu (zachowuje komentarze)
                        List<String> keyLines = extractKeyLines(defaultLines, key);
                        if (!keyLines.isEmpty()) {
                            newLines.addAll(keyLines);
                        } else {
                            // Fallback: generuj ręcznie
                            String subKey = key.substring(section.length() + 1);
                            Object val = defaultConfig.get(key);
                            // Oblicz wcięcie na podstawie głębokości klucza
                            int depth = key.split("\\.").length - 1;
                            String indent = "  ".repeat(depth);
                            newLines.add(indent + subKey + ": " + toYaml(val));
                        }
                    }
                    userLines.addAll(insertAt, newLines);
                }
            }

            // Usuń cały blok config-version (komentarze dekoracyjne + sam klucz)
            removeConfigVersionBlock(userLines);

            // Usuń puste linie na końcu
            while (!userLines.isEmpty() && userLines.get(userLines.size() - 1).trim().isEmpty()) {
                userLines.remove(userLines.size() - 1);
            }

            // Dodaj cały blok config-version z domyślnego configu na samym końcu
            List<String> versionBlock = extractSection(defaultLines, "config-version");
            userLines.add("");
            if (!versionBlock.isEmpty()) {
                // Podmień wartość wersji w wyciągniętym bloku
                for (int i = 0; i < versionBlock.size(); i++) {
                    if (versionBlock.get(i).trim().startsWith("config-version:")) {
                        versionBlock.set(i, versionBlock.get(i).replaceAll("config-version:.*",
                                "config-version: " + defaultVersion));
                    }
                }
                userLines.addAll(versionBlock);
            } else {
                userLines.add("config-version: " + defaultVersion);
            }

            // Zapisz
            Files.write(configFile.toPath(), userLines, StandardCharsets.UTF_8);
            plugin.reloadConfig();
            logger.info("[ConfigUpdater] Config zaktualizowany pomyslnie!");

        } catch (Exception e) {
            logger.severe("[ConfigUpdater] BLAD: " + e.getMessage());
            e.printStackTrace();

            // Ostateczny fallback: użyj Bukkit API
            try {
                logger.info("[ConfigUpdater] Probuje fallback przez Bukkit API...");
                FileConfiguration config = YamlConfiguration.loadConfiguration(configFile);
                for (String key : findMissingKeys(config, defaultConfig)) {
                    config.set(key, defaultConfig.get(key));
                }
                config.set("config-version", defaultVersion);
                config.save(configFile);
                plugin.reloadConfig();
                logger.info("[ConfigUpdater] Fallback zakonczony (komentarze mogly zostac usuniete).");
            } catch (Exception ex) {
                logger.severe("[ConfigUpdater] Fallback tez sie nie udal: " + ex.getMessage());
            }
        }
    }

    private void updateConfigVersionInFile(File configFile, int version) {
        try {
            List<String> lines = new ArrayList<>(
                    Files.readAllLines(configFile.toPath(), StandardCharsets.UTF_8));

            // Usuń cały blok config-version
            removeConfigVersionBlock(lines);

            // Usuń puste linie na końcu
            while (!lines.isEmpty() && lines.get(lines.size() - 1).trim().isEmpty()) {
                lines.remove(lines.size() - 1);
            }

            // Dodaj cały blok z domyślnego configu na końcu
            List<String> defaultLines = readResourceLines();
            List<String> versionBlock = (defaultLines != null) ? extractSection(defaultLines, "config-version")
                    : new ArrayList<>();
            lines.add("");
            if (!versionBlock.isEmpty()) {
                for (int i = 0; i < versionBlock.size(); i++) {
                    if (versionBlock.get(i).trim().startsWith("config-version:")) {
                        versionBlock.set(i,
                                versionBlock.get(i).replaceAll("config-version:.*", "config-version: " + version));
                    }
                }
                lines.addAll(versionBlock);
            } else {
                lines.add("config-version: " + version);
            }
            Files.write(configFile.toPath(), lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.severe("[ConfigUpdater] Blad zapisu config-version: " + e.getMessage());
        }
    }

    /**
     * Usuwa cały blok config-version z listy linii
     * (sam klucz + wszystkie komentarze i puste linie nad nim)
     */
    private void removeConfigVersionBlock(List<String> lines) {
        int versionLine = -1;
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (lines.get(i).trim().startsWith("config-version:")) {
                versionLine = i;
                break;
            }
        }
        if (versionLine == -1)
            return;

        // Znajdź początek bloku (komentarze i puste linie nad config-version)
        int blockStart = versionLine;
        while (blockStart > 0) {
            String prev = lines.get(blockStart - 1).trim();
            if (prev.startsWith("#") || prev.isEmpty()) {
                blockStart--;
            } else {
                break;
            }
        }

        // Usuń cały blok od końca
        for (int i = versionLine; i >= blockStart; i--) {
            lines.remove(i);
        }
    }

    private List<String> readResourceLines() {
        List<String> lines = new ArrayList<>();
        try (InputStream is = plugin.getResource("config.yml")) {
            if (is == null) {
                logger.warning("[ConfigUpdater] plugin.getResource('config.yml') zwrocilo null!");
                return null;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            }
        } catch (Exception e) {
            logger.severe("[ConfigUpdater] Blad czytania zasobu: " + e.getMessage());
            return null;
        }
        return lines;
    }

    private FileConfiguration loadYamlFromLines(List<String> lines) {
        String joined = String.join("\n", lines);
        return YamlConfiguration.loadConfiguration(new StringReader(joined));
    }

    private List<String> findMissingKeys(FileConfiguration userConfig, FileConfiguration defaultConfig) {
        List<String> missing = new ArrayList<>();
        for (String key : defaultConfig.getKeys(true)) {
            if (defaultConfig.isConfigurationSection(key))
                continue;
            if (key.equals("config-version"))
                continue;
            if (!userConfig.contains(key)) {
                missing.add(key);
            }
        }
        return missing;
    }

    private boolean lineExists(List<String> lines, String prefix) {
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith(prefix) && !trimmed.startsWith("#")) {
                return true;
            }
        }
        return false;
    }

    private List<String> extractSection(List<String> lines, String sectionName) {
        List<String> result = new ArrayList<>();

        int sectionStart = -1;
        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            if (!trimmed.startsWith("#") && trimmed.startsWith(sectionName + ":") && getIndent(lines.get(i)) == 0) {
                sectionStart = i;
                break;
            }
        }
        if (sectionStart == -1) {
            logger.warning("[ConfigUpdater] Nie znaleziono sekcji '" + sectionName + "' w domyslnym configu!");
            return result;
        }

        // Komentarze nad sekcją
        int commentStart = sectionStart;
        while (commentStart > 0) {
            String prev = lines.get(commentStart - 1).trim();
            if (prev.startsWith("#") || prev.isEmpty()) {
                commentStart--;
            } else {
                break;
            }
        }
        // Pomiń puste linie na samym początku
        while (commentStart < sectionStart && lines.get(commentStart).trim().isEmpty()) {
            commentStart++;
        }

        // Nagłówek + komentarze
        for (int i = commentStart; i <= sectionStart; i++) {
            result.add(lines.get(i));
        }

        // Dzieci sekcji
        for (int i = sectionStart + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (getIndent(line) > 0 || line.trim().isEmpty()) {
                result.add(line);
            } else {
                break;
            }
        }

        // Usuń puste linie na końcu
        while (!result.isEmpty() && result.get(result.size() - 1).trim().isEmpty()) {
            result.remove(result.size() - 1);
        }

        return result;
    }

    /**
     * Wyciąga linie dla konkretnego klucza z domyślnego configu.
     * Np. dla klucza "messages.update-available" szuka linii " update-available:" w
     * sekcji messages.
     * Zachowuje komentarze nad kluczem.
     */
    private List<String> extractKeyLines(List<String> lines, String yamlPath) {
        List<String> result = new ArrayList<>();
        String[] parts = yamlPath.split("\\.");

        // Szukamy ostatniego segmentu klucza z odpowiednim wcięciem
        String leafKey = parts[parts.length - 1];
        int expectedIndent = (parts.length - 1) * 2; // 2 spacje na poziom

        // Znajdź sekcję nadrzędną
        int searchStart = 0;
        for (int p = 0; p < parts.length - 1; p++) {
            String parentKey = parts[p];
            int parentIndent = p * 2;
            boolean found = false;
            for (int i = searchStart; i < lines.size(); i++) {
                String line = lines.get(i);
                String trimmed = line.trim();
                if (!trimmed.startsWith("#") && getIndent(line) == parentIndent
                        && trimmed.startsWith(parentKey + ":")) {
                    searchStart = i + 1;
                    found = true;
                    break;
                }
            }
            if (!found)
                return result;
        }

        // Szukaj klucza liścia
        for (int i = searchStart; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();

            // Sprawdź czy wyszliśmy z sekcji
            if (!trimmed.isEmpty() && !trimmed.startsWith("#") && getIndent(line) < expectedIndent) {
                break;
            }

            if (getIndent(line) == expectedIndent && !trimmed.startsWith("#")
                    && trimmed.startsWith(leafKey + ":")) {
                // Znaleziono klucz — dodaj komentarze nad nim
                int commentStart = i;
                while (commentStart > searchStart) {
                    String prev = lines.get(commentStart - 1).trim();
                    if (prev.startsWith("#") || prev.isEmpty()) {
                        commentStart--;
                    } else {
                        break;
                    }
                }
                // Pomiń puste linie na początku
                while (commentStart < i && lines.get(commentStart).trim().isEmpty()) {
                    commentStart++;
                }

                // Dodaj komentarze i sam klucz
                for (int j = commentStart; j <= i; j++) {
                    result.add(lines.get(j));
                }

                // Dodaj dzieci klucza (jeśli ma zagnieżdżone wartości)
                for (int j = i + 1; j < lines.size(); j++) {
                    String child = lines.get(j);
                    if (getIndent(child) > expectedIndent || child.trim().isEmpty()) {
                        result.add(child);
                    } else {
                        break;
                    }
                }

                // Usuń puste linie na końcu
                while (!result.isEmpty() && result.get(result.size() - 1).trim().isEmpty()) {
                    result.remove(result.size() - 1);
                }

                return result;
            }
        }

        return result;
    }

    private int findSectionEnd(List<String> lines, String sectionName) {
        boolean inSection = false;
        int lastChildLine = -1;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();

            if (!inSection) {
                if (getIndent(line) == 0 && !trimmed.startsWith("#") && trimmed.startsWith(sectionName + ":")) {
                    inSection = true;
                    lastChildLine = i;
                }
            } else {
                if (!trimmed.isEmpty() && !trimmed.startsWith("#") && getIndent(line) == 0) {
                    // Dotarliśmy do następnej sekcji top-level
                    return lastChildLine + 1;
                }
                // Śledź ostatnią linię z zawartością (wcięta = dziecko sekcji)
                if (!trimmed.isEmpty() && !trimmed.startsWith("#") && getIndent(line) > 0) {
                    lastChildLine = i;
                }
            }
        }
        return lastChildLine + 1;
    }

    private String toYaml(Object value) {
        if (value == null)
            return "\"\"";
        if (value instanceof String) {
            String s = (String) value;
            return "\"" + s.replace("\"", "\\\"") + "\"";
        }
        if (value instanceof List) {
            StringBuilder sb = new StringBuilder("\n");
            for (Object item : (List<?>) value) {
                sb.append("    - \"").append(item).append("\"\n");
            }
            return sb.toString().stripTrailing();
        }
        return String.valueOf(value);
    }

    private int getIndent(String line) {
        int count = 0;
        for (char c : line.toCharArray()) {
            if (c == ' ')
                count++;
            else
                break;
        }
        return count;
    }
}
