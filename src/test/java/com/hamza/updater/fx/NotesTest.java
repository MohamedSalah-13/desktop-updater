package com.hamza.updater.fx;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotesTest {

    private static List<String> kinds(String markdown) {
        return Notes.read(markdown).stream().map(block -> block.kind() + ":" + block.text()).toList();
    }

    @Test
    void headingsParagraphsAndListsBecomeBlocks() {
        List<Notes.Block> blocks = Notes.read("""
                ## What's new

                Files are now encrypted
                on the server.

                - Send several files
                  - also by drag and drop
                * Search in a chat
                1. Update the server
                2) Then the clients

                ---
                > Back up first.
                """);
        assertEquals(List.of("HEADING:What's new", "PARAGRAPH:Files are now encrypted on the server.",
                        "BULLET:Send several files", "BULLET:also by drag and drop", "BULLET:Search in a chat",
                        "NUMBERED:Update the server", "NUMBERED:Then the clients", "RULE:", "QUOTE:Back up first."),
                blocks.stream().map(block -> block.kind() + ":" + block.text()).toList());
        assertEquals(2, blocks.get(0).level());
        assertEquals("\u2022", blocks.get(2).marker());
        assertEquals(0, blocks.get(2).level());
        assertEquals(1, blocks.get(3).level());
        assertEquals("1.", blocks.get(5).marker());
        assertEquals("2.", blocks.get(6).marker());
    }

    @Test
    void aLineRightUnderAListItemContinuesIt() {
        assertEquals(List.of("BULLET:Closing a statement: rows before the date are locked, and it can be opened again.",
                        "NUMBERED:Update the server first, then the clients.", "PARAGRAPH:After a blank line, a paragraph."),
                kinds("""
                        - **Closing a statement:** rows before the date are locked,
                          and it can be opened again.
                        1. Update the server first,
                        then the clients.

                        After a blank line, a paragraph.
                        """));
    }

    @Test
    void theMarksOfMarkdownAreNotShown() {
        List<Notes.Span> spans = Notes.spans("Now **fast** and *safe*, see `release.ps1` or __this__ and _that_.");
        assertEquals("Now fast and safe, see release.ps1 or this and that.",
                String.join("", spans.stream().map(Notes.Span::text).toList()));
        assertTrue(spans.stream().anyMatch(span -> span.text().equals("fast") && span.bold() && !span.italic()));
        assertTrue(spans.stream().anyMatch(span -> span.text().equals("safe") && span.italic() && !span.bold()));
        assertTrue(spans.stream().anyMatch(span -> span.text().equals("release.ps1") && span.code()));
        assertTrue(spans.stream().anyMatch(span -> span.text().equals("this") && span.bold()));
        assertTrue(spans.stream().anyMatch(span -> span.text().equals("that") && span.italic()));
    }

    @Test
    void whatOnlyLooksLikeAMarkStaysAsWritten() {
        assertEquals("the file_name_here and 2 * 3 * 4 and a lone * star", Notes.read("the file_name_here and 2 * 3 * 4 and a lone * star").get(0).text());
        assertEquals("snake_case_name", Notes.spans("snake_case_name").get(0).text());
        assertEquals("C:\\Users\\me and *not italic*", Notes.read("C:\\Users\\me and \\*not italic\\*").get(0).text());
        assertEquals("an unclosed **bold and `code", Notes.read("an unclosed **bold and `code").get(0).text());
    }

    @Test
    void linksOpenWebAddressesAndNothingElse() {
        List<Notes.Span> spans = Notes.spans("See [the guide](https://example.com/guide) or https://example.com/a?b=1. Not [this](javascript:alert(1)).");
        Notes.Span guide = spans.stream().filter(span -> span.text().equals("the guide")).findFirst().orElseThrow();
        assertEquals("https://example.com/guide", guide.link());
        Notes.Span bare = spans.stream().filter(span -> span.text().startsWith("https://example.com/a")).findFirst().orElseThrow();
        assertEquals("https://example.com/a?b=1", bare.link(), "the full stop after it is not part of the address");
        assertNull(spans.stream().filter(span -> span.text().equals("this")).findFirst().orElseThrow().link());
    }

    @Test
    void githubsGeneratedNotesRead() {
        List<Notes.Block> blocks = Notes.read("""
                ## What's Changed
                * Add a release script by @someone in https://github.com/owner/app/pull/2

                **Full Changelog**: https://github.com/owner/app/compare/v2.5.0...v2.6.0
                """);
        assertEquals(3, blocks.size());
        assertEquals("Add a release script by @someone in https://github.com/owner/app/pull/2", blocks.get(1).text());
        assertEquals("https://github.com/owner/app/pull/2", blocks.get(1).spans().get(1).link());
        assertTrue(blocks.get(2).spans().get(0).bold());
        assertEquals("https://github.com/owner/app/compare/v2.5.0...v2.6.0", blocks.get(2).spans().get(2).link());
    }

    @Test
    void codeIsKeptAsWritten() {
        List<Notes.Block> blocks = Notes.read("Run:\n```powershell\n.\\release.ps1 -DryRun   # **not bold**\n  second line\n```\nDone.");
        assertEquals(List.of("PARAGRAPH:Run:", "CODE:.\\release.ps1 -DryRun   # **not bold**\n  second line", "PARAGRAPH:Done."),
                kinds("Run:\n```powershell\n.\\release.ps1 -DryRun   # **not bold**\n  second line\n```\nDone."));
        assertTrue(blocks.get(1).spans().get(0).code());
        assertEquals(List.of("CODE:left open"), kinds("```\nleft open"));
    }

    @Test
    void tagsImagesTickBoxesAndEntities() {
        assertEquals(List.of("PARAGRAPH:Details inside", "BULLET:done", "BULLET:to do", "PARAGRAPH:the logo and a < b & c"),
                kinds("<details><summary>Details</summary> inside</details>\n\n- [x] done\n- [ ] to do\n\n![the logo](https://example.com/logo.png) and a &lt; b &amp; c"));
        List<Notes.Block> boxes = Notes.read("- [x] done\n- [ ] to do");
        assertEquals("\u2611", boxes.get(0).marker());
        assertEquals("\u2610", boxes.get(1).marker());
    }

    @Test
    void arabicNotesReadRightToLeft() {
        assertTrue(Notes.rightToLeft("## الجديد\n- إرسال عدة ملفات"));
        assertTrue(Notes.rightToLeft("- **3.0** يغيّر البروتوكول"));
        assertFalse(Notes.rightToLeft("## What's new\n- عربي لاحقًا"));
        assertFalse(Notes.rightToLeft("123 --- "));
        List<Notes.Block> blocks = Notes.read("## ⚠️ اقرأ قبل التحديث\n\n- **حدّث السيرفر الأول، وبعده العملاء.** 3.0 غيّر البروتوكول.");
        assertEquals("⚠️ اقرأ قبل التحديث", blocks.get(0).text());
        assertTrue(blocks.get(1).spans().get(0).bold());
    }

    @Test
    void nothingAndNullAreEmpty() {
        assertTrue(Notes.read("").isEmpty());
        assertTrue(Notes.read(null).isEmpty());
        assertTrue(Notes.read("\n\n   \n").isEmpty());
    }
}
