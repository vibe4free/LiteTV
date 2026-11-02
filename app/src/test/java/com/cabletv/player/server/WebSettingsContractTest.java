package com.cabletv.player.server;

import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Keeps the four places a setting lives in step.
 *
 * <p>The JSON of {@code /config/get}, the web page and the stored keys are written by hand, so
 * the contract is checked rather than generated: an entry added to {@link WebSetting} without a
 * JSON field or without a control on the page fails here instead of shipping as a setting nobody
 * can reach.
 */
@RunWith(RobolectricTestRunner.class)
public class WebSettingsContractTest {

    private String pageHtml() throws Exception {
        try (InputStream in = RuntimeEnvironment.getApplication().getAssets()
                .open("web/index.html")) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private JsonObject configJson() {
        // Package-visible on purpose: this is the exact payload the page receives.
        return JsonParser.parseString(ConfigWebServer.getConfigJson()).getAsJsonObject();
    }

    @Test
    public void everySettingIsReadableThroughTheJson() {
        JsonObject json = configJson();
        for (WebSetting setting : WebSetting.values()) {
            assertTrue("settings read back by the page must include " + setting.field,
                    json.has(setting.field));
            assertTrue("the stored key must be declared for " + setting.field,
                    setting.configKey.startsWith("app_"));
        }
    }

    @Test
    public void everySettingHasAControlOnThePage() throws Exception {
        String html = pageHtml();
        for (WebSetting setting : WebSetting.values()) {
            // The page refers to a field either as a form field name in a request body
            // ('field=') or as the id of the control carrying it.
            boolean referenced = html.contains("'" + setting.field + "='")
                    || html.contains("&" + setting.field + "=")
                    || html.contains("\"" + setting.field + "\"")
                    || html.contains("'" + setting.field + "'");
            assertTrue("the page has no control for " + setting.field, referenced);
        }
    }

    @Test
    public void thePageOnlySendsFieldsTheServerKnows() throws Exception {
        String html = pageHtml();
        // A field the page builds as "<name>=" has to be one the server reads; a rename on one
        // side used to fail silently, because the server ignored what it did not know.
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("([a-z][a-z0-9_]+)='").matcher(html);
        java.util.Set<String> unknown = new java.util.TreeSet<>();
        while (matcher.find()) {
            String field = matcher.group(1);
            if (WebSetting.byField(field) == null) {
                unknown.add(field);
            }
        }
        assertTrue("the page sends fields the server does not read: " + unknown,
                unknown.isEmpty());
    }
}
