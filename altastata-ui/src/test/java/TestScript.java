/*
 * Copyright (c) 2026 AltaStata Inc. All rights reserved.
 *
 * This software is dual-licensed. It is licensed under the Business Source License 1.1 
 * (BSL) for open use and evaluation, with an eventual transition to the Apache 2.0 
 * license on the Change Date.
 * 
 * PATENT NOTICE: Protected by US Patent No. 10,693,660.
 *
 * For the full license text, see the LICENSE.md file in the root of the repository,
 * or https://github.com/AltaStata/sovereign-data-fabric/blob/main/LICENSE.md
 */

/**
 * Based on https://henning.kropponline.de/2016/04/17/scripting-scala-jsr-223/
 */

import java.io.BufferedReader;
import java.io.FileReader;

import javax.script.*;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;

import scala.tools.nsc.interpreter.IMain;
import scala.tools.nsc.settings.MutableSettings.BooleanSetting;

public class TestScript {

	public static void main(String... args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: TestScript <account-dir> <password> <scala-script>");
            System.exit(1);
        }
        ScriptEngine engine = new ScriptEngineManager().getEngineByName("scala");

        ((BooleanSetting)(((IMain)engine)
               .settings().usejavacp()))
                    .value_$eq(true);

        String testScript = "var a:Int =  10";
        engine.eval(testScript);

        String testScript2 = "println(a)";
        engine.eval(testScript2);

        String testScript3 = "println(a+5)";
        engine.eval(testScript3);

        engine.eval("val accountDir: String = \"" + args[0].replace("\\", "\\\\") + "\"");
        engine.eval("val password: String = \"" + args[1].replace("\\", "\\\\") + "\"");

        BufferedReader br = new BufferedReader(new FileReader(args[2]));
        engine.eval(br);
    }
}
