package hu.demo.reviewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class DiffUtilTest {

    @Test
    void numbersAddedAndContextLinesOnly() {
        String patch = "@@ -1,3 +10,3 @@\n context\n-old\n+new\n tail";
        DiffUtil.Annotated a = DiffUtil.annotate(patch);

        assertEquals(Set.of(10, 11, 12), a.commentableLines());
        assertTrue(a.text().contains("L11| +new"));
    }

    @Test
    void extractsJsonFromFencedAnswer() {
        assertEquals("{\"a\":1}", Main.extractJson("```json\n{\"a\":1}\n```"));
    }
}
