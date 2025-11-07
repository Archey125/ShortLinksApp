package org.example;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

public class UrlShortenerApp {

    // Базовый домен
    private static final String SHORT_BASE = "clck.ru/";
    // Настраиваемый TTL через системное свойство (-Dttl.seconds=...)
    private final Duration ttl = Duration.ofSeconds(Long.getLong("ttl.seconds", 24 * 60 * 60L));

    // Хранилище ссылок: slug -> ShortLink
    private final ConcurrentMap<String, ShortLink> links = new ConcurrentHashMap<>();
    // Индекс: userId -> set(slug)
    private final ConcurrentMap<UUID, Set<String>> userIndex = new ConcurrentHashMap<>();
    // Уведомления пользователя
    private final ConcurrentMap<UUID, Deque<Notification>> inbox = new ConcurrentHashMap<>();
    // Генератор
    private final SlugGenerator slugGen = new SlugGenerator();

    // Планировщик очистки ссылок
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "link-cleaner");
        t.setDaemon(true);
        return t;
    });

    //Формат времени для печати
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    public static void main(String[] args) {
        new UrlShortenerApp().run();
    }

    public UrlShortenerApp() {
        scheduler.scheduleAtFixedRate(this::cleanupExpired, 10, 30, TimeUnit.SECONDS);
    }

    private void run() {
        System.out.println("Консольный сокращатель ссылок. Введите 'help' для списка команд.");
        System.out.println("TTL по умолчанию: " + ttl.toHours() + " ч (переопределите через -Dttl.seconds=...)");
        try (Scanner sc = new Scanner(System.in)) {
            while (true) {
                System.out.print("\n> ");
                if (!sc.hasNextLine()) break;
                String line = sc.nextLine().trim();
                if (line.isEmpty()) continue;

                if (line.equalsIgnoreCase("exit") || line.equalsIgnoreCase("quit")) {
                    System.out.println("Завершение работы...");
                    shutdown();
                    break;
                }
                if (line.equalsIgnoreCase("help")) {
                    printHelp();
                    continue;
                }
                try {
                    handleCommand(line);
                } catch (Exception e) {
                    System.out.println("Ошибка: " + e.getMessage());
                }
            }
        }
    }

    private void shutdown() {
        scheduler.shutdownNow();
    }

    private void printHelp() {
        System.out.println("""
                Доступные команды:
                  new <url> [--limit N] [--user <uuid>]      Создать короткую ссылку. Если --user не указан, будет сгенерирован новый UUID.
                  open <short|slug>                          Перейти по короткой ссылке (открывает браузер), учитывает лимит и TTL.
                  list --user <uuid>                         Показать все ссылки пользователя.
                  delete <short|slug> --user <uuid>          Удалить ссылку (только автор).
                  notifications --user <uuid>                Показать уведомления пользователя.
                  help                                       Показать помощь.
                  exit                                       Выход.
                Примеры:
                  new https://www.baeldung.com/java-9-http-client --limit 3
                  new https://example.com --user 11111111-1111-1111-1111-111111111111
                  open clck.ru/Ab9ZxQ1
                  list --user 11111111-1111-1111-1111-111111111111
                """);
    }

    private void handleCommand(String line) throws Exception {
        List<String> tokens = split(line);
        if (tokens.isEmpty()) return;

        String cmd = tokens.get(0).toLowerCase(Locale.ROOT);
        switch (cmd) {
            case "new" -> cmdNew(tokens);
            case "open" -> cmdOpen(tokens);
            case "list" -> cmdList(tokens);
            case "delete" -> cmdDelete(tokens);
            case "notifications" -> cmdNotifications(tokens);
            default -> System.out.println("Неизвестная команда. Введите 'help'.");
        }
    }

    // --- Команды ---

    private void cmdNew(List<String> tokens) throws URISyntaxException {
        if (tokens.size() < 2) {
            System.out.println("Использование: new <url> [--limit N] [--user <uuid>]");
            return;
        }
        String urlStr = tokens.get(1);
        URI uri = new URI(urlStr);
        if (!uri.isAbsolute()) {
            throw new IllegalArgumentException("URL должен быть абсолютным (схема + хост): " + urlStr);
        }

        Integer limit = null;
        UUID userId = null;

        for (int i = 2; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if ("--limit".equalsIgnoreCase(t) && i + 1 < tokens.size()) {
                limit = Integer.parseInt(tokens.get(++i));
                if (limit < 1) throw new IllegalArgumentException("Лимит должен быть >= 1");
            } else if ("--user".equalsIgnoreCase(t) && i + 1 < tokens.size()) {
                userId = UUID.fromString(tokens.get(++i));
            } else {
                throw new IllegalArgumentException("Неизвестный параметр: " + t);
            }
        }

        boolean newUser = false;
        if (userId == null) {
            userId = UUID.randomUUID();
            newUser = true;
        }

        ShortLink link = createShortLink(userId, uri, limit);

        System.out.println((newUser ? "Сгенерирован UUID пользователя: " + userId + "\n" : "")
                + "Короткая ссылка: " + formatShortUrl(link.slug)
                + "\nОригинал:       " + link.original
                + "\nЛимит:          " + (link.maxClicks == null ? "без ограничения" : link.maxClicks)
                + "\nДоступно:       " + (link.remainingClicks == null ? "∞" : link.remainingClicks)
                + "\nСрок жизни до:  " + TS.format(link.expiresAt));
    }

    private void cmdOpen(List<String> tokens) {
        if (tokens.size() < 2) {
            System.out.println("Использование: open <short|slug>");
            return;
        }
        String raw = tokens.get(1);
        String slug = extractSlug(raw);

        ShortLink link = links.get(slug);
        if (link == null) {
            System.out.println("Ссылка не найдена: " + raw);
            return;
        }

        // Проверка TTL
        if (isExpired(link)) {
            deleteLink(slug); // удаляем
            notifyUser(link.userId, "Ваша ссылка " + formatShortUrl(slug) + " истекла и была удалена.");
            System.out.println("Ссылка недоступна: срок жизни истек.");
            return;
        }

        // Проверка лимита
        if (link.remainingClicks != null && link.remainingClicks <= 0) {
            notifyUser(link.userId, "Лимит переходов по ссылке " + formatShortUrl(slug) + " исчерпан.");
            System.out.println("Ссылка недоступна: лимит переходов исчерпан.");
            return;
        }

        //Переход по ссылке
        if (link.remainingClicks != null) {
            link.remainingClicks -= 1;
        }
        try {
            System.out.println("Открываю: " + link.original);
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(link.original);
            } else {
                System.out.println("Desktop не поддерживается в вашей среде. Откройте вручную: " + link.original);
            }
        } catch (IOException e) {
            System.out.println("Не удалось открыть браузер: " + e.getMessage());
        }
        // Если после перехода лимит стал 0 — уведомим автора
        if (link.remainingClicks != null && link.remainingClicks == 0) {
            notifyUser(link.userId, "Лимит переходов по ссылке " + formatShortUrl(slug) + " исчерпан.");
        }
    }

    private void cmdList(List<String> tokens) {
        UUID userId = requireUser(tokens);
        if (userId == null) return;

        Set<String> slugs = userIndex.getOrDefault(userId, Collections.emptySet());
        if (slugs.isEmpty()) {
            System.out.println("Нет ссылок для пользователя " + userId);
            return;
        }

        System.out.println("Ссылки пользователя " + userId + ":");
        for (String slug : slugs) {
            ShortLink l = links.get(slug);
            if (l == null) continue;
            boolean expired = isExpired(l);
            System.out.printf(Locale.ROOT,
                    "- %s -> %s | лимит: %s, остаток: %s, истекает: %s%s%n",
                    formatShortUrl(slug),
                    l.original,
                    l.maxClicks == null ? "∞" : l.maxClicks,
                    l.remainingClicks == null ? "∞" : l.remainingClicks,
                    TS.format(l.expiresAt),
                    expired ? " [ПРОСРОЧЕНО]" : "");
        }
    }

    private void cmdDelete(List<String> tokens) {
        if (tokens.size() < 2) {
            System.out.println("Использование: delete <short|slug> --user <uuid>");
            return;
        }
        String raw = tokens.get(1);
        String slug = extractSlug(raw);

        UUID userId = requireUser(tokens);
        if (userId == null) return;

        ShortLink l = links.get(slug);
        if (l == null) {
            System.out.println("Ссылка не найдена: " + raw);
            return;
        }
        if (!l.userId.equals(userId)) {
            System.out.println("Удаление запрещено: ссылка принадлежит другому пользователю.");
            return;
        }

        deleteLink(slug);
        System.out.println("Ссылка удалена: " + formatShortUrl(slug));
    }

    private void cmdNotifications(List<String> tokens) {
        UUID userId = requireUser(tokens);
        if (userId == null) return;

        Deque<Notification> queue = inbox.getOrDefault(userId, new ArrayDeque<>());
        if (queue.isEmpty()) {
            System.out.println("Нет уведомлений.");
            return;
        }
        System.out.println("Уведомления:");
        while (!queue.isEmpty()) {
            Notification n = queue.pollFirst();
            System.out.println("[" + TS.format(n.timestamp) + "] " + n.message);
        }
    }

    // --- Логика сервиса ---

    private ShortLink createShortLink(UUID userId, URI url, Integer maxClicks) {
        String slug;
        do {
            slug = slugGen.nextSlug();
        } while (links.containsKey(slug));

        Instant now = Instant.now();
        ShortLink link = new ShortLink();
        link.slug = slug;
        link.original = url;
        link.userId = userId;
        link.maxClicks = maxClicks;                         // null => без ограничения
        link.remainingClicks = maxClicks;                   // null => без ограничения
        link.createdAt = now;
        link.expiresAt = now.plus(ttl);

        links.put(slug, link);
        userIndex.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(slug);
        return link;
    }

    private boolean isExpired(ShortLink l) {
        return Instant.now().isAfter(l.expiresAt);
    }

    private void cleanupExpired() {
        try {
            Instant now = Instant.now();
            List<ShortLink> expired = new ArrayList<>();
            for (ShortLink l : links.values()) {
                if (now.isAfter(l.expiresAt)) {
                    expired.add(l);
                }
            }
            for (ShortLink l : expired) {
                deleteLink(l.slug);
                notifyUser(l.userId, "Ваша ссылка " + formatShortUrl(l.slug) + " истекла и была удалена.");
            }
            if (!expired.isEmpty()) {
                System.out.println("Очистка завершена: удалено " + expired.size() + " просроченных ссылок.");
            }
        } catch (Exception e) {
            System.out.println("Ошибка очистки: " + e.getMessage());
        }
    }

    private void deleteLink(String slug) {
        ShortLink removed = links.remove(slug);
        if (removed != null) {
            Set<String> set = userIndex.get(removed.userId);
            if (set != null) set.remove(slug);
        }
    }

    private void notifyUser(UUID userId, String message) {
        inbox.computeIfAbsent(userId, k -> new ArrayDeque<>())
                .addLast(new Notification(Instant.now(), message));
        // Дополнительно дублируем в консоль
        System.out.println("[Уведомление для " + userId + "] " + message);
    }

    private String formatShortUrl(String slug) {
        return SHORT_BASE + slug;
    }

    private String extractSlug(String raw) {
        if (raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("clck.ru/")) {
            int idx = raw.lastIndexOf('/');
            if (idx != -1 && idx + 1 < raw.length()) {
                return raw.substring(idx + 1);
            }
        }
        // Если передали просто slug, без clck.ru и https
        return raw;
    }

    private UUID requireUser(List<String> tokens) {
        for (int i = 1; i < tokens.size(); i++) {
            if ("--user".equalsIgnoreCase(tokens.get(i)) && i + 1 < tokens.size()) {
                try {
                    return UUID.fromString(tokens.get(i + 1));
                } catch (IllegalArgumentException e) {
                    System.out.println("Некорректный UUID: " + tokens.get(i + 1));
                    return null;
                }
            }
        }
        System.out.println("Нужно указать --user <uuid>.");
        return null;
    }

    private static List<String> split(String line) {
        //токенизирование по пробелам
        return new ArrayList<>(Arrays.asList(line.split("\\s+")));
    }

    // --- Вспомогательные классы ---

    private static class ShortLink {
        String slug;
        URI original;
        UUID userId;
        Integer maxClicks;       // null => без ограничения
        Integer remainingClicks; // null => без ограничения
        Instant createdAt;
        Instant expiresAt;
    }

    private record Notification(Instant timestamp, String message) { }

    private static class SlugGenerator {
        private static final char[] ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();
        private static final int LENGTH = 8;
        private final SecureRandom random = new SecureRandom();

        String nextSlug() {
            char[] buf = new char[LENGTH];
            for (int i = 0; i < LENGTH; i++) {
                buf[i] = ALPHABET[random.nextInt(ALPHABET.length)];
            }
            return new String(buf);
        }
    }
}

//