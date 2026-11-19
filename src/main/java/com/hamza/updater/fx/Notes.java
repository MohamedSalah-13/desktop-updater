package com.hamza.updater.fx;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A release's notes, read from the Markdown a publisher writes on GitHub into what a person should see:
 * headings, paragraphs, lists, quotes and code, with bold, italic, code and links inside them.
 *
 * <p>Enough of Markdown for release notes, not all of it: a table stays text, an image becomes its
 * description, and HTML tags are dropped. Whatever is not understood is shown as it was written, so
 * nothing a publisher wrote is lost.
 *
 * <p>Nothing here touches JavaFX; {@link NotesView} draws what this reads.
 */
final class Notes {

    enum Kind { HEADING, PARAGRAPH, BULLET, NUMBERED, QUOTE, CODE, RULE }

    /**
     * @param level  a heading's rank (1 the largest), or how deep a list item is nested (0 the outermost)
     * @param marker what stands before a list item: a bullet, a tick box, or its number
     */
    record Block(Kind kind, int level, String marker, List<Span> spans) {
        String text() {
            StringBuilder text = new StringBuilder();
            spans.forEach(span -> text.append(span.text()));
            return text.toString();
        }
    }

    /** @param link the address the text leads to, or null */
    record Span(String text, boolean bold, boolean italic, boolean code, String link) {
    }

    private static final Pattern HEADING = Pattern.compile("^ {0,3}(#{1,6})\\s+(.*?)\\s*#*$");
    private static final Pattern BULLET = Pattern.compile("^(\\s*)[-*+]\\s+(.*)$");
    private static final Pattern NUMBERED = Pattern.compile("^(\\s*)(\\d{1,4})[.)]\\s+(.*)$");
    private static final Pattern RULE = Pattern.compile("^ {0,3}([-*_])( *\\1){2,} *$");
    private static final Pattern QUOTE = Pattern.compile("^ {0,3}>\\s?(.*)$");
    private static final Pattern FENCE = Pattern.compile("^ {0,3}(```|~~~).*$");
    private static final Pattern TAG = Pattern.compile("</?[A-Za-z][^>]*>");
    private static final Pattern LINE_BREAK_TAG = Pattern.compile("(?i)<br\\s*/?>");

    private Notes() {
    }

    static List<Block> read(String markdown) {
        List<Block> blocks = new ArrayList<>();
        if (markdown == null) {
            return blocks;
        }
        StringBuilder paragraph = new StringBuilder();
        StringBuilder code = null;
        for (String raw : markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            if (code != null) {
                if (FENCE.matcher(raw).matches()) {
                    blocks.add(new Block(Kind.CODE, 0, "", List.of(new Span(code.toString().stripTrailing(), false, false, true, null))));
                    code = null;
                } else {
                    code.append(raw).append('\n');
                }
                continue;
            }
            if (FENCE.matcher(raw).matches()) {
                paragraph(blocks, paragraph);
                code = new StringBuilder();
                continue;
            }
            String line = entities(TAG.matcher(LINE_BREAK_TAG.matcher(raw).replaceAll(" ")).replaceAll(""));
            Matcher matcher;
            if (line.isBlank()) {
                paragraph(blocks, paragraph);
            } else if (RULE.matcher(line).matches()) {
                paragraph(blocks, paragraph);
                blocks.add(new Block(Kind.RULE, 0, "", List.of()));
            } else if ((matcher = HEADING.matcher(line)).matches()) {
                paragraph(blocks, paragraph);
                blocks.add(new Block(Kind.HEADING, matcher.group(1).length(), "", spans(matcher.group(2))));
            } else if ((matcher = BULLET.matcher(line)).matches()) {
                paragraph(blocks, paragraph);
                String item = matcher.group(2);
                String marker = "•";
                if (item.matches("^\\[[ xX]]\\s+.*")) {
                    marker = item.charAt(1) == ' ' ? "☐" : "☑";
                    item = item.substring(3).stripLeading();
                }
                blocks.add(new Block(Kind.BULLET, depth(matcher.group(1)), marker, spans(item)));
            } else if ((matcher = NUMBERED.matcher(line)).matches()) {
                paragraph(blocks, paragraph);
                blocks.add(new Block(Kind.NUMBERED, depth(matcher.group(1)), matcher.group(2) + ".", spans(matcher.group(3))));
            } else if ((matcher = QUOTE.matcher(line)).matches()) {
                paragraph(blocks, paragraph);
                blocks.add(new Block(Kind.QUOTE, 0, "", spans(matcher.group(1))));
            } else {
                if (paragraph.length() > 0) {
                    paragraph.append(' ');
                }
                paragraph.append(line.strip());
            }
        }
        if (code != null) {
            // A fence nobody closed: what followed it is still code.
            blocks.add(new Block(Kind.CODE, 0, "", List.of(new Span(code.toString().stripTrailing(), false, false, true, null))));
        }
        paragraph(blocks, paragraph);
        return blocks;
    }

    /** Whether the notes are written right to left: their first letter is Arabic or Hebrew. */
    static boolean rightToLeft(String text) {
        for (int i = 0; i < text.length(); ) {
            int point = text.codePointAt(i);
            byte direction = Character.getDirectionality(point);
            if (direction == Character.DIRECTIONALITY_RIGHT_TO_LEFT
                    || direction == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) {
                return true;
            }
            if (direction == Character.DIRECTIONALITY_LEFT_TO_RIGHT) {
                return false;
            }
            i += Character.charCount(point);
        }
        return false;
    }

    private static void paragraph(List<Block> blocks, StringBuilder paragraph) {
        if (paragraph.length() > 0) {
            blocks.add(new Block(Kind.PARAGRAPH, 0, "", spans(paragraph.toString())));
            paragraph.setLength(0);
        }
    }

    /** Two blanks, or a tab, for each step in. */
    private static int depth(String indent) {
        return Math.min(4, indent.replace("\t", "  ").length() / 2);
    }

    private static String entities(String text) {
        if (text.indexOf('&') < 0) {
            return text;
        }
        return text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&nbsp;", " ").replace("&amp;", "&");
    }

    static List<Span> spans(String text) {
        List<Span> spans = new ArrayList<>();
        inline(text, false, false, null, spans);
        return spans;
    }

    private static void inline(String text, boolean bold, boolean italic, String link, List<Span> out) {
        StringBuilder plain = new StringBuilder();
        int at = 0;
        int length = text.length();
        while (at < length) {
            char c = text.charAt(at);
            if (c == '\\' && at + 1 < length && "\\`*_{}[]()#+-.!>~|".indexOf(text.charAt(at + 1)) >= 0) {
                plain.append(text.charAt(at + 1));
                at += 2;
                continue;
            }
            if (c == '`') {
                int end = text.indexOf('`', at + 1);
                if (end > at + 1) {
                    flush(plain, bold, italic, link, out);
                    out.add(new Span(text.substring(at + 1, end), bold, italic, true, link));
                    at = end + 1;
                    continue;
                }
            }
            if (c == '[' || (c == '!' && at + 1 < length && text.charAt(at + 1) == '[')) {
                int open = c == '!' ? at + 1 : at;
                int middle = text.indexOf("](", open);
                int close = middle < 0 ? -1 : text.indexOf(')', middle + 2);
                if (middle > open && close > middle && text.indexOf('\n', open) < 0) {
                    String label = text.substring(open + 1, middle);
                    String address = text.substring(middle + 2, close).strip().split("\\s", 2)[0];
                    flush(plain, bold, italic, link, out);
                    if (c == '!') {
                        // An image cannot be shown; its description can.
                        inline(label, bold, italic, link, out);
                    } else {
                        inline(label, bold, italic, safeAddress(address) ? address : link, out);
                    }
                    at = close + 1;
                    continue;
                }
            }
            if ((c == '*' || c == '_') && at + 1 < length && text.charAt(at + 1) == c) {
                int end = text.indexOf(String.valueOf(c).repeat(2), at + 2);
                if (end > at + 2 && (c == '*' || wordEdges(text, at, end + 1))) {
                    flush(plain, bold, italic, link, out);
                    inline(text.substring(at + 2, end), true, italic, link, out);
                    at = end + 2;
                    continue;
                }
            }
            if ((c == '*' || c == '_') && at + 1 < length && !Character.isWhitespace(text.charAt(at + 1))) {
                int end = closer(text, c, at + 1);
                if (end > at + 1 && (c == '*' || wordEdges(text, at, end))) {
                    flush(plain, bold, italic, link, out);
                    inline(text.substring(at + 1, end), bold, true, link, out);
                    at = end + 1;
                    continue;
                }
            }
            if (link == null && (text.startsWith("https://", at) || text.startsWith("http://", at))) {
                int end = at;
                while (end < length && !Character.isWhitespace(text.charAt(end)) && "<>\"'".indexOf(text.charAt(end)) < 0) {
                    end++;
                }
                while (end > at && ".,;:!?)".indexOf(text.charAt(end - 1)) >= 0) {
                    end--;
                }
                String address = text.substring(at, end);
                flush(plain, bold, italic, link, out);
                out.add(new Span(address, bold, italic, false, safeAddress(address) ? address : null));
                at = end;
                continue;
            }
            plain.append(c);
            at++;
        }
        flush(plain, bold, italic, link, out);
    }

    /** The single mark that ends an emphasis: not doubled, and with a letter before it. */
    private static int closer(String text, char mark, int from) {
        for (int i = from; i < text.length(); i++) {
            if (text.charAt(i) != mark || Character.isWhitespace(text.charAt(i - 1))) {
                continue;
            }
            boolean doubled = (i + 1 < text.length() && text.charAt(i + 1) == mark) || text.charAt(i - 1) == mark;
            if (!doubled) {
                return i;
            }
        }
        return -1;
    }

    /** An underscore inside a word - a file_name - is a letter, not a mark. */
    private static boolean wordEdges(String text, int open, int close) {
        boolean before = open == 0 || !Character.isLetterOrDigit(text.charAt(open - 1));
        boolean after = close + 1 >= text.length() || !Character.isLetterOrDigit(text.charAt(close + 1));
        return before && after;
    }

    /** Only a web address is ever opened; anything else stays text. */
    private static boolean safeAddress(String address) {
        return address.startsWith("https://") || address.startsWith("http://");
    }

    private static void flush(StringBuilder plain, boolean bold, boolean italic, String link, List<Span> out) {
        if (plain.length() > 0) {
            out.add(new Span(plain.toString(), bold, italic, false, link));
            plain.setLength(0);
        }
    }
}
