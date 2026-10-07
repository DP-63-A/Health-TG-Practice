package org.healthtg.bot.recognition;

/** Deliberately returns the supplied recorded JSON, independent of image content. */
public final class FixtureRecognitionProvider implements RecognitionProvider {
    private final String json;
    public FixtureRecognitionProvider(String json) { this.json = json; }
    public static FixtureRecognitionProvider bundled() {
        return new FixtureRecognitionProvider(RecognitionJson.resource("fixture.json"));
    }
    @Override public Mode mode() { return Mode.FIXTURE; }
    @Override public Response recognize(ImageValidator.ValidatedImage image) { return new Response(json, null, null); }
}
