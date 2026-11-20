package portfolio.habit;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.*;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class Tracker {
    private final JdbcTemplate db;
    private final BCryptPasswordEncoder passwords;

    public Tracker(JdbcTemplate db, BCryptPasswordEncoder passwords) {
        this.db = db;
        this.passwords = passwords;
    }

    public record Habit(long id, String name, String category, int schedule, LocalDate created, LocalDate archived,
                        NavigableMap<LocalDate, Integer> schedules) {
        public String repeatLabel() {
            if (schedule == 127) return "Every day";
            return Arrays.stream(DayOfWeek.values()).filter(day -> scheduled(day.getValue()))
                .map(day -> day.getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH))
                .collect(java.util.stream.Collectors.joining(", "));
        }
        public boolean scheduled(int weekday) {
            return (schedule & (1 << (weekday - 1))) != 0;
        }
        public boolean due(LocalDate day) {
            var version = schedules.floorEntry(day);
            int mask = version == null ? schedule : version.getValue();
            return !day.isBefore(created) && (archived == null || day.isBefore(archived))
                && (mask & (1 << (day.getDayOfWeek().getValue() - 1))) != 0;
        }
    }

    public record Day(LocalDate date, boolean done, boolean due) {}
    public record Progress(Habit habit, boolean done, int current, int best, int completed, int due, List<Day> days) {}

    @GetMapping("/health")
    @ResponseBody
    Map<String, String> health() {
        db.queryForObject("SELECT 1", Integer.class);
        return Map.of("status", "ok");
    }

    @GetMapping("/login")
    String login() { return "auth"; }

    @GetMapping("/register")
    String register(Model model) {
        registration(model);
        return "auth";
    }

    private void registration(Model model) {
        model.addAttribute("register", true);
        model.addAttribute("zones", ZoneId.getAvailableZoneIds().stream().sorted().toList());
    }

    @PostMapping("/register")
    String register(@RequestParam String email, @RequestParam String password, @RequestParam String zone, Model model) {
        registration(model);
        email = email.strip().toLowerCase(Locale.ROOT);
        if (email.length() > 150 || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")
                || password.length() < 12 || password.getBytes(StandardCharsets.UTF_8).length > 72
                || !ZoneId.getAvailableZoneIds().contains(zone)) {
            model.addAttribute("error", "Enter a valid email, time zone, and a password of 12 or more characters (up to 72 bytes).");
            return "auth";
        }
        try {
            db.update("INSERT INTO accounts VALUES (?,?,?)", email, passwords.encode(password), zone);
            return "redirect:/login?created";
        } catch (DuplicateKeyException exception) {
            model.addAttribute("error", "An account already exists for that email.");
            return "auth";
        }
    }

    private LocalDate today(Principal user) {
        return LocalDate.now(ZoneId.of(db.queryForObject("SELECT zone FROM accounts WHERE email=?", String.class, user.getName())));
    }

    private List<Habit> habits(Principal user) {
        Map<Long, NavigableMap<LocalDate, Integer>> schedules = new HashMap<>();
        db.query("SELECT s.habit_id,s.effective_on,s.schedule FROM habit_schedules s JOIN habits h ON h.id=s.habit_id WHERE h.owner=?",
            row -> { schedules.computeIfAbsent(row.getLong(1), key -> new TreeMap<>())
                .put(row.getObject(2, LocalDate.class), row.getInt(3)); }, user.getName());
        return db.query("SELECT id,name,schedule,created,archived,category FROM habits WHERE owner=? ORDER BY archived NULLS FIRST,id DESC",
            (r, n) -> new Habit(r.getLong(1), r.getString(2), r.getString(6), r.getInt(3), r.getObject(4, LocalDate.class),
                r.getObject(5, LocalDate.class), schedules.getOrDefault(r.getLong(1), Collections.emptyNavigableMap())), user.getName());
    }

    private static final List<String> CATEGORIES = List.of("Uncategorized", "Health", "Study", "Fitness", "Personal", "Work");

    public record CalendarDay(LocalDate date, int completed, int scheduled, boolean future) {}

    @GetMapping({"/", "/progress", "/calendar"})
    String dashboard(Principal user, Model model, jakarta.servlet.http.HttpServletRequest request,
                     @RequestParam(required=false) Long edit,
                     @RequestParam(defaultValue="") String category,
                     @RequestParam(required=false) String month,
                     @RequestParam(required=false) String day) {
        LocalDate today = today(user);
        List<Habit> allHabits = habits(user);
        List<Habit> habits = allHabits.stream().filter(h -> category.isEmpty() || h.category().equals(category)).toList();
        Map<Long, Set<LocalDate>> entries = new HashMap<>();
        db.query("SELECT c.habit_id,c.check_date FROM checkins c JOIN habits h ON h.id=c.habit_id WHERE h.owner=?",
            r -> { entries.computeIfAbsent(r.getLong(1), k -> new HashSet<>()).add(r.getObject(2, LocalDate.class)); },
            user.getName());
        List<Progress> progress = habits.stream().map(h -> progress(h, entries.getOrDefault(h.id(), Set.of()), today)).toList();
        List<Progress> due = progress.stream().filter(h -> h.habit().archived() == null && (h.habit().due(today) || h.done())).toList();
        long complete = due.stream().filter(Progress::done).count();
        model.addAttribute("today", today);
        model.addAttribute("categories", CATEGORIES);
        model.addAttribute("category", category);
        model.addAttribute("calendarView", request.getServletPath().equals("/calendar"));
        model.addAttribute("currentPath", request.getServletPath());
        YearMonth selectedMonth;
        LocalDate selectedDay;
        try {
            selectedMonth = month == null ? YearMonth.from(today) : YearMonth.parse(month);
            if (selectedMonth.getYear() < 1900 || selectedMonth.getYear() > 9998) throw new IllegalArgumentException();
            selectedDay = day == null ? (selectedMonth.equals(YearMonth.from(today)) ? today : selectedMonth.atDay(1)) : LocalDate.parse(day);
            if (!YearMonth.from(selectedDay).equals(selectedMonth)) selectedDay = selectedMonth.atDay(1);
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid calendar date");
        }
        List<CalendarDay> calendarDays = new ArrayList<>();
        for (int index = 1; index <= selectedMonth.lengthOfMonth(); index++) {
            LocalDate date = selectedMonth.atDay(index);
            int doneCount = 0, dueCount = 0;
            for (Habit habit : habits) {
                boolean done = entries.getOrDefault(habit.id(), Set.of()).contains(date);
                if (done) doneCount++;
                if (habit.due(date) || done) dueCount++;
            }
            calendarDays.add(new CalendarDay(date, doneCount, dueCount, date.isAfter(today)));
        }
        LocalDate dateToShow = selectedDay;
        model.addAttribute("selectedMonth", selectedMonth);
        model.addAttribute("previousMonth", selectedMonth.minusMonths(1));
        model.addAttribute("nextMonth", selectedMonth.plusMonths(1));
        model.addAttribute("calendarDays", calendarDays);
        model.addAttribute("blankDays", Collections.nCopies(selectedMonth.atDay(1).getDayOfWeek().getValue() - 1, ""));
        model.addAttribute("selectedDay", selectedDay);
        model.addAttribute("dayHabits", habits.stream().filter(h -> h.due(dateToShow)
            || entries.getOrDefault(h.id(), Set.of()).contains(dateToShow)).toList());
        model.addAttribute("entries", entries);
        model.addAttribute("due", due);
        model.addAttribute("complete", complete);
        model.addAttribute("percent", due.isEmpty() ? 0 : 100 * complete / due.size());
        model.addAttribute("progress", progress);
        model.addAttribute("activeHabits", habits.stream().filter(h -> h.archived() == null).toList());
        model.addAttribute("weeklyDone", progress.stream().mapToInt(Progress::completed).sum());
        model.addAttribute("weeklyDue", progress.stream().mapToInt(Progress::due).sum());
        model.addAttribute("history", request.getServletPath().equals("/progress"));
        model.addAttribute("editing", allHabits.stream().filter(h -> Objects.equals(h.id(), edit) && h.archived() == null).findFirst().orElse(null));
        model.addAttribute("weekdays", DayOfWeek.values());
        return "dashboard";
    }

    static Progress progress(Habit habit, Set<LocalDate> entries, LocalDate today) {
        int run = 0, best = 0, completed = 0, due = 0;
        for (LocalDate day = habit.created(); !day.isAfter(today); day = day.plusDays(1)) {
            if (entries.contains(day)) {
                best = Math.max(best, ++run);
            } else if (habit.due(day) && day.isBefore(today)) {
                run = 0;
            }
            if (!day.isBefore(today.minusDays(6)) && (habit.due(day) || entries.contains(day))) {
                due++;
                if (entries.contains(day)) completed++;
            }
        }
        List<Day> days = new ArrayList<>();
        for (int offset = 27; offset >= 0; offset--) {
            LocalDate date = today.minusDays(offset);
            days.add(new Day(date, entries.contains(date), habit.due(date)));
        }
        return new Progress(habit, entries.contains(today), habit.archived() == null ? run : 0, best, completed, due, days);
    }

    @Transactional
    @PostMapping("/habits")
    String save(Principal user, @RequestParam(required=false) Long id, @RequestParam String name,
                @RequestParam(defaultValue="Uncategorized") String category,
                @RequestParam(defaultValue="") List<Integer> weekdays) {
        name = name.strip();
        if (!CATEGORIES.contains(category) || name.isEmpty() || name.length() > 80 || weekdays.isEmpty()
                || weekdays.stream().anyMatch(day -> day < 1 || day > 7)) {
            return "redirect:/?invalid" + (id == null ? "" : "&edit=" + id);
        }
        int schedule = weekdays.stream().reduce(0, (mask, day) -> mask | (1 << (day - 1)));
        if (id == null) {
            db.update("INSERT INTO habits(owner,name,schedule,created,category) VALUES (?,?,?,?,?)",
                user.getName(), name, schedule, today(user), category);
        } else {
            var existing = db.query("SELECT created,schedule FROM habits WHERE id=? AND owner=? AND archived IS NULL FOR UPDATE",
                (row, n) -> Map.entry(row.getObject(1, LocalDate.class), row.getInt(2)), id, user.getName());
            if (existing.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            var previous = existing.get(0);
            if (previous.getValue() != schedule) {
                LocalDate effective = today(user);
                db.update("INSERT INTO habit_schedules(habit_id,effective_on,schedule) SELECT ?,?,? WHERE NOT EXISTS (SELECT 1 FROM habit_schedules WHERE habit_id=?)",
                    id, previous.getKey(), previous.getValue(), id);
                db.update("DELETE FROM habit_schedules WHERE habit_id=? AND effective_on=?", id, effective);
                db.update("INSERT INTO habit_schedules VALUES (?,?,?)", id, effective, schedule);
            }
            requireChanged(db.update("UPDATE habits SET name=?,schedule=?,category=? WHERE id=? AND owner=? AND archived IS NULL",
                name, schedule, category, id, user.getName()));
        }
        return "redirect:/";
    }

    @PostMapping("/habits/{id}/archive")
    String archive(Principal user, @PathVariable long id) {
        requireChanged(db.update("UPDATE habits SET archived=? WHERE id=? AND owner=? AND archived IS NULL",
            today(user), id, user.getName()));
        return "redirect:/progress";
    }

    @Transactional
    @PostMapping("/habits/{id}/check")
    String check(Principal user, @PathVariable long id, @RequestParam boolean done) {
        LocalDate day = today(user);
        List<Integer> schedule = db.queryForList(
            "SELECT schedule FROM habits WHERE id=? AND owner=? AND archived IS NULL AND created<=? FOR UPDATE",
            Integer.class, id, user.getName(), day);
        if (schedule.isEmpty() || (done && (schedule.get(0) & (1 << (day.getDayOfWeek().getValue()-1))) == 0)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (done) {
            db.update("INSERT INTO checkins(habit_id,check_date) SELECT ?,? WHERE NOT EXISTS (SELECT 1 FROM checkins WHERE habit_id=? AND check_date=?)",
                id, day, id, day);
        } else {
            db.update("DELETE FROM checkins WHERE habit_id=? AND check_date=?", id, day);
        }
        return "redirect:/";
    }

    private void requireChanged(int count) {
        if (count == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
}
