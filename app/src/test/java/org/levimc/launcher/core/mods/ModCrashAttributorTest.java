package org.levimc.launcher.core.mods;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class ModCrashAttributorTest {

    @Test
    public void flagsEnabledModMentionedInLog() {
        Mod smoothBoot = new Mod("smoothboot", "SmoothBoot.so", "libsmoothboot.so", "Smooth Boot", true, 0);
        Mod fpsBoost = new Mod("fpsboost", "FpsBoost.so", "libfpsboost.so", "FPS Boost", true, 1);
        List<Mod> flagged = ModCrashAttributor.attribute(
                "Fatal signal 11 (SIGSEGV) in tid 1234, code 1\n  #00 pc 000000000012ab00  libsmoothboot.so",
                Arrays.asList(smoothBoot, fpsBoost));

        assertTrue(flagged.contains(smoothBoot));
        assertFalse(flagged.contains(fpsBoost));
    }

    @Test
    public void ignoresDisabledModsAndShortTokens() {
        Mod disabled = new Mod("xy", "Xy.so", "libxy.so", "Xy Mod", false, 0);
        Mod shortName = new Mod("ab", "Ab.so", "libab.so", "Ab", true, 1);
        List<Mod> flagged = ModCrashAttributor.attribute(
                "Ab everywhere and Xy Mod here", Arrays.asList(disabled, shortName));

        assertTrue(flagged.isEmpty());
    }

    @Test
    public void flagsModByDisplayNameOrIdCaseInsensitively() {
        Mod byName = new Mod("a", "a.so", "liba.so", "Script Injector", true, 0);
        Mod byId = new Mod("levishaders", "b.so", "libb.so", "B", true, 1);

        List<Mod> flagged = ModCrashAttributor.attribute(
                "SCRIPT: error in script injector module\nLeviShaders pack failed",
                Arrays.asList(byName, byId));

        assertTrue(flagged.contains(byName));
        assertTrue(flagged.contains(byId));
    }

    @Test
    public void handlesNullInputs() {
        assertTrue(ModCrashAttributor.attribute(null, Collections.emptyList()).isEmpty());
        assertTrue(ModCrashAttributor.attribute("log", null).isEmpty());
    }
}
