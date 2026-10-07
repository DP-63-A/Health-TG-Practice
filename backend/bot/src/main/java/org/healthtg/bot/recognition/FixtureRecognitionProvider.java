package org.healthtg.bot.recognition;

/** Deliberately returns the supplied recorded JSON, independent of image content. */
public final class FixtureRecognitionProvider implements RecognitionProvider {
    private final String json;
    private final boolean bundled;
    public FixtureRecognitionProvider(String json) { this(json,false); }
    private FixtureRecognitionProvider(String json,boolean bundled) { this.json=json; this.bundled=bundled; }
    public static FixtureRecognitionProvider bundled() {
        return new FixtureRecognitionProvider(RecognitionJson.resource("fixture.json"),true);
    }
    @Override public Mode mode() { return Mode.FIXTURE; }
    @Override public Response recognize(ImageValidator.ValidatedImage image) { return new Response(json, null, null); }
    @Override public Response recognize(ImageValidator.ValidatedImage image,String imageClass) {
        if(bundled && java.util.Set.of("health_screenshot","watch_photo").contains(java.util.Objects.toString(imageClass,"")))
            return new Response(RecognitionJson.resource("fixture-metrics.json").replace("health_screenshot",imageClass),null,null);
        return recognize(image);
    }
}
