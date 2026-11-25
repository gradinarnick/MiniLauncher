package com.kolya.minilauncher;

/** Правило для одного приложения. Хранится строкой: allow, pause:20, limit:15, sched:540:1080, block. */
final class Rule {
    static final int ALLOW = 0, PAUSE = 1, LIMIT = 2, SCHED = 3, BLOCK = 4;

    final int type;
    /** PAUSE — секунды, LIMIT — минуты в день, SCHED — начало (минуты от полуночи). */
    final int a;
    /** SCHED — конец (минуты от полуночи). */
    final int b;

    Rule(int type, int a, int b) {
        this.type = type;
        this.a = a;
        this.b = b;
    }

    static Rule parse(String s) {
        if (s != null) {
            try {
                String[] p = s.split(":");
                switch (p[0]) {
                    case "pause": return new Rule(PAUSE, Integer.parseInt(p[1]), 0);
                    case "limit": return new Rule(LIMIT, Integer.parseInt(p[1]), 0);
                    case "sched": return new Rule(SCHED, Integer.parseInt(p[1]), Integer.parseInt(p[2]));
                    case "block": return new Rule(BLOCK, 0, 0);
                    default: break;
                }
            } catch (Exception ignored) {
                // повреждённая запись — считаем «без ограничений»
            }
        }
        return new Rule(ALLOW, 0, 0);
    }

    String encode() {
        switch (type) {
            case PAUSE: return "pause:" + a;
            case LIMIT: return "limit:" + a;
            case SCHED: return "sched:" + a + ":" + b;
            case BLOCK: return "block";
            default: return "allow";
        }
    }

    String describe() {
        switch (type) {
            case PAUSE: return "Пауза " + a + " сек";
            case LIMIT: return a + " мин в день";
            case SCHED: return "Закрыто " + Guard.hhmm(a) + "–" + Guard.hhmm(b);
            case BLOCK: return "Заблокировано";
            default: return "Без ограничений";
        }
    }

    int color() {
        switch (type) {
            case PAUSE: return Ui.AMBER;
            case LIMIT: return 0xFFBE185D;
            case SCHED: return 0xFF0369A1;
            case BLOCK: return Ui.RED;
            default: return Ui.SUB;
        }
    }

    int soft() {
        switch (type) {
            case PAUSE: return Ui.AMBER_SOFT;
            case LIMIT: return 0xFFFCE7F3;
            case SCHED: return 0xFFE0F2FE;
            case BLOCK: return Ui.RED_SOFT;
            default: return Ui.GRAY_SOFT;
        }
    }

    int illustration() {
        switch (type) {
            case PAUSE: return R.drawable.ill_hourglass;
            case LIMIT: return R.drawable.ill_timer;
            case SCHED: return R.drawable.ill_clock;
            case BLOCK: return R.drawable.ill_block;
            default: return R.drawable.ill_check;
        }
    }

    /** Новое правило не мягче старого — менять можно без кода. */
    static boolean notLooser(Rule old, Rule now) {
        if (old.type == ALLOW || now.type == BLOCK) return true;
        if (old.type != now.type) return false;
        switch (now.type) {
            case PAUSE: return now.a >= old.a;
            case LIMIT: return now.a <= old.a;
            case SCHED: return now.a == old.a && now.b == old.b;
            default: return true;
        }
    }
}
