package org.healthtg.bot.recognition;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.healthtg.bot.recognition.RecognitionException.Code.*;

class ImageValidatorTest {
    @TempDir Path temp;
    private final ImageValidator validator=new ImageValidator();
    static byte[] image(String format,int width,int height) throws Exception {
        var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
        image.setRGB(0,0,0xFF00FF); var bytes=new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image,format,bytes));image.flush();return bytes.toByteArray();
    }
    @ParameterizedTest @ValueSource(strings={"png","jpeg"})
    void supportedFormatsUseContentRatherThanExtensionAndDefensiveBytes(String format) throws Exception {
        byte[] input=image(format,4,3);Path p=temp.resolve("печёное 🍎.wrong");Files.write(p,input);
        var result=validator.validate(p);assertEquals("image/"+format,result.mimeType());assertArrayEquals(input,result.bytes());
        byte original=result.bytes()[0];input[0]=0;result.bytes()[0]=0;assertEquals(original,result.bytes()[0]);
    }
    @Test void exactPixelLimitAcceptedButHigherResolutionRejectedBeforeDecode() throws Exception {
        assertEquals("image/png",validator.validate(image("png",4000,3000)).mimeType());
        assertEquals(TOO_MANY_PIXELS,assertThrows(RecognitionException.class,()->validator.validate(image("png",4001,3000))).code());
    }
    @Test void exactByteLimitAcceptedAndOneByteOverRejected() throws Exception {
        byte[] png=image("png",1,1);
        // Legal ancillary PNG chunk (with a correct CRC) creates a decodable file exactly at the limit.
        byte[] padded=padPng(png,ImageValidator.MAX_BYTES);
        assertEquals(ImageValidator.MAX_BYTES,validator.validate(padded).bytes().length);
        assertEquals(IMAGE_TOO_LARGE,assertThrows(RecognitionException.class,()->validator.validate(padPng(png,ImageValidator.MAX_BYTES+1))).code());
    }
    private static byte[] padPng(byte[] png,int length) throws Exception {
        int dataLength=length-png.length-12;var bytes=new ByteArrayOutputStream();var out=new java.io.DataOutputStream(bytes);
        out.write(png,0,png.length-12);out.writeInt(dataLength);byte[] type={'p','a','D','d'};out.write(type);
        byte[] content=new byte[dataLength];out.write(content);var crc=new java.util.zip.CRC32();crc.update(type);crc.update(content);out.writeInt((int)crc.getValue());
        out.write(png,png.length-12,12);return bytes.toByteArray();
    }
    @Test void missingEmptyUnsupportedAndCorruptRejectBeforeProvider() throws Exception {
        var calls=new AtomicInteger();RecognitionProvider provider=new RecognitionProvider(){
            public Mode mode(){return Mode.FIXTURE;} public Response recognize(ImageValidator.ValidatedImage image){calls.incrementAndGet();return new Response("{}",null,null);}
        };
        var service=new FoodRecognitionService(validator,provider,new RecognitionResponseParser());
        byte[] png=image("png",2,2);
        byte[][] cases={new byte[0],"not an image".getBytes(java.nio.charset.StandardCharsets.UTF_8),image("gif",2,2),Arrays.copyOf(png,25),new byte[ImageValidator.MAX_BYTES+1],image("png",4001,3000)};
        for(int i=0;i<cases.length;i++){Path p=temp.resolve("invalid"+i);Files.write(p,cases[i]);assertThrows(RecognitionException.class,()->service.recognize(p));}
        assertThrows(RecognitionException.class,()->service.recognize(temp.resolve("missing")));assertEquals(0,calls.get());
    }
    @ParameterizedTest @ValueSource(strings={"png","jpeg"})
    void truncatedEndRejectedEvenIfImageIoCanDecode(String format) throws Exception {
        byte[] bytes=image(format,10,10);byte[] truncated=Arrays.copyOf(bytes,bytes.length-(format.equals("png")?12:2));
        assertEquals(INVALID_IMAGE,assertThrows(RecognitionException.class,()->validator.validate(truncated)).code());
    }
    @Test void corruptPngCrcRejected() throws Exception {
        byte[] bytes=image("png",3,3);bytes[29]^=1; // IHDR CRC, pixel dimensions remain intact.
        assertEquals(INVALID_IMAGE,assertThrows(RecognitionException.class,()->validator.validate(bytes)).code());
    }
}
