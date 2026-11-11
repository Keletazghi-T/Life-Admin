package com.lifeadmin.service;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
@Component
public class ReceiptFileValidator {
    public record Validated(byte[] content,String originalName,String contentType,String extension) {}
    public Validated validate(MultipartFile file) throws IOException {
        if(file.isEmpty()) throw bad("Choose a PDF, JPEG or PNG receipt that is not empty.");
        if(file.getSize()>10*1024*1024) throw large();
        String name=Optional.ofNullable(file.getOriginalFilename()).orElse("").replace('\\','/');
        name=name.substring(name.lastIndexOf('/')+1).replaceAll("[\\p{Cntrl}\\p{Cf}]","").strip();
        if(name.isBlank() || name.length()>255) throw bad("Choose a receipt filename of at most 255 characters.");
        String lower=name.toLowerCase(Locale.ROOT);String extension=lower.endsWith(".pdf")?"pdf":lower.endsWith(".png")?"png":lower.endsWith(".jpg")||lower.endsWith(".jpeg")?"jpg":"";
        String type=switch(extension){case "pdf"->"application/pdf";case "jpg"->"image/jpeg";case "png"->"image/png";default->throw bad("Only PDF, JPEG and PNG receipts are supported.");};
        if(!type.equalsIgnoreCase(file.getContentType()))throw bad("The receipt file type does not match its filename.");
        byte[] bytes;try(var in=file.getInputStream()){bytes=in.readNBytes(10*1024*1024+1);}if(bytes.length>10*1024*1024)throw large();
        if(extension.equals("pdf")) {
            if(bytes.length<5 || !new String(bytes,0,5,java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-"))throw bad("Choose a valid PDF receipt.");
            try(var pdf=org.apache.pdfbox.Loader.loadPDF(bytes)) {if(pdf.getNumberOfPages()<1)throw bad("Choose a PDF with at least one page.");}
            catch(IOException|IllegalArgumentException e){throw bad("This PDF cannot be read. Choose a valid, unencrypted PDF receipt.");}
        } else {
            // Decode only after checking dimensions, to bound memory usage for compressed image bombs.
            try(var in=new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers=ImageIO.getImageReaders(in);if(!readers.hasNext())throw bad("Choose a valid JPEG or PNG image.");
                var reader=readers.next();try {
                    reader.setInput(in,true,true);String format=reader.getFormatName();
                    if(!(extension.equals("jpg")?format.equalsIgnoreCase("JPEG"):format.equalsIgnoreCase("PNG")))throw bad("The receipt image content does not match its type.");
                    int width=reader.getWidth(0),height=reader.getHeight(0);
                    if(width<1 || height<1 || (long)width*height>16_000_000)throw bad("Choose a receipt image of 16 megapixels or smaller.");
                    var image=reader.read(0);if(image==null)throw bad("This receipt image cannot be read.");image.flush();
                }finally{reader.dispose();}
            }catch(IOException|IllegalArgumentException e){throw bad("This receipt image cannot be read. Choose a valid JPEG or PNG.");}
        }
        return new Validated(bytes,name,type,extension);
    }
    private ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
    private ResponseStatusException large(){return new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"Receipts must be 10 MB or smaller.");}
}
