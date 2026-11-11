package com.lifeadmin.service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="app.storage-type",havingValue="local",matchIfMissing=true)
public class LocalDocumentStorage implements DocumentStorage {
    private final Path root;
    public LocalDocumentStorage(@Value("${app.storage-directory}") String directory) throws IOException {
        root = Path.of(directory).toAbsolutePath().normalize();
        Files.createDirectories(root);
    }
    public String store(byte[] content) { return store(content,"pdf"); }
    public String store(byte[] content,String extension) {
        if(!java.util.Set.of("pdf","jpg","png").contains(extension)) throw new IllegalArgumentException("Unsupported storage extension");
        String name = UUID.randomUUID() + "." + extension;
        Path destination = resolve(name);
        try {
            Files.write(destination, content, StandardOpenOption.CREATE_NEW);
            return name;
        } catch (IOException e) {
            try { Files.deleteIfExists(destination); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            throw new IllegalStateException("Could not store document", e);
        }
    }
    public byte[] read(String name) {
        try (var input = Files.newInputStream(resolve(name), LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(10 * 1024 * 1024 + 1);
            if (bytes.length > 10 * 1024 * 1024)
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "Stored file exceeds the 10 MB limit.");
            return bytes;
        } catch (IOException e) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.GONE, "The stored file is unavailable. Please upload it again.");
        }
    }
    public FileState inspect(String name,long expectedSize,String contentType) {
        try {
            Path path=resolve(name);
            var attributes=Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(!attributes.isRegularFile() || attributes.size()!=expectedSize || attributes.size()==0 || attributes.size()>10*1024*1024) return FileState.INVALID;
            byte[] prefix;try(var input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){prefix=input.readNBytes(8);}
            boolean valid=switch(contentType) {
                case "application/pdf" -> prefix.length>=5 && new String(prefix,0,5,java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-");
                case "image/png" -> java.util.Arrays.equals(prefix,new byte[]{(byte)137,80,78,71,13,10,26,10});
                case "image/jpeg" -> prefix.length>=3 && prefix[0]==(byte)255 && prefix[1]==(byte)216 && prefix[2]==(byte)255;
                default -> false;
            };
            return valid?FileState.AVAILABLE:FileState.INVALID;
        } catch(NoSuchFileException e) {return FileState.MISSING;}
        catch(IllegalArgumentException e) {return FileState.INVALID;}
        catch(IOException|SecurityException e) {return FileState.UNAVAILABLE;}
    }
    public void delete(String name) {
        try { Files.deleteIfExists(resolve(name)); }
        catch (IOException e) { throw new IllegalStateException("Could not remove document", e); }
    }
    private Path resolve(String name) {
        if (name == null || !name.matches("[a-f0-9-]{36}\\.(pdf|jpg|png)")) throw new IllegalArgumentException("Invalid storage identifier");
        Path path = root.resolve(name).normalize();
        if (!path.getParent().equals(root)) throw new IllegalArgumentException("Invalid storage path");
        return path;
    }
}
