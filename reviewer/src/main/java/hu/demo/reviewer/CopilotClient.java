package hu.demo.reviewer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A GitHub Copilot CLI-t hívja programozott módban ("copilot -p"), így a Copilot-előfizetés
 * modelljét használja, külön LLM-szolgáltatói fiók nélkül. Hitelesítés: COPILOT_GITHUB_TOKEN
 * környezeti változó ("Copilot Requests" jogosultságú fine-grained PAT).
 *
 * <p>Eszközengedélyt nem adunk át, ezért a CLI fájlt nem írhat, parancsot nem futtathat: csak szöveget ad vissza.
 */
final class CopilotClient {

    private final String command;
    private final String model;

    CopilotClient(String command, String model) {
        this.command = command;
        this.model = model;
    }

    /** Elküldi a system és user promptot egyetlen promptként, visszaadja a CLI szöveges válaszát. */
    String complete(String system, String user) throws IOException, InterruptedException {
        String prompt = system
                + "\n\nFONTOS: ne használj eszközöket (fájlolvasás, shell), kizárólag az alábbi bemenetet dolgozd fel."
                + "\n\n" + user;

        List<String> cmd = new ArrayList<>(List.of(command, "-p", prompt, "-s", "--no-ask-user"));
        if (model != null && !model.isBlank()) {
            cmd.add("--model");
            cmd.add(model);
        }

        Process process = new ProcessBuilder(cmd)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        process.getOutputStream().close();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(5, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new IllegalStateException("A Copilot CLI időtúllépés miatt megszakítva.");
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("A Copilot CLI hibával kilépett (" + process.exitValue() + "): " + output);
        }
        return output;
    }
}
