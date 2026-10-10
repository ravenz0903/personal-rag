package com.tracker.rag.service.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

/** Original files are private: downloaded through the authenticated backend only. */
@Service
public class DocumentStorageService {
    private final String url, key, bucket;
    private final boolean cloud;
    private final Path root;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    public DocumentStorageService(Environment env, @Value("${supabase.url:}") String url,
            @Value("${supabase.service-key:}") String key, @Value("${supabase.storage.bucket:documents}") String bucket,
            @Value("${tracker.documents.root:${FOLIO_DATA_DIR:data}/documents}") String documentsRoot) {
        this.root = Path.of(documentsRoot).toAbsolutePath().normalize();
        this.url = url; this.key = key; this.bucket = bucket;
        this.cloud = Arrays.asList(env.getActiveProfiles()).contains("cloud");
        if (cloud && (key.isBlank() || !url.startsWith("https://"))) throw new IllegalStateException("Private document storage requires Supabase credentials");
        if (!bucket.matches("[a-zA-Z0-9_-]+")) throw new IllegalArgumentException("Invalid storage bucket");
    }
    public String save(byte[] bytes, String domain, String filename, String contentType) {
        if (bytes.length > 5 * 1024 * 1024) throw new IllegalArgumentException("Document must be smaller than 5 MB");
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            String safeName = filename.replaceAll("[^a-zA-Z0-9._-]", "_");
            String objectKey = domain + "/" + hash + "/" + safeName;
            if (cloud) {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url + "/storage/v1/object/" + bucket + "/" + objectKey))
                        .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + key)
                        .header("apikey", key).header("x-upsert", "true").header("Content-Type", contentType)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
                int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
                if (status < 200 || status >= 300) throw new IllegalStateException("Storage rejected upload");
            } else {
                Path destination = resolve(objectKey); Files.createDirectories(destination.getParent());
                Path temporary=Files.createTempFile(destination.getParent(),"upload-",".tmp");
                try{Files.write(temporary,bytes);Files.move(temporary,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temporary);}
            }
            return objectKey;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Document upload interrupted");
        } catch (Exception e) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Could not store the original document. Check storage settings and limits."); }
    }
    public byte[] read(String objectKey) {
        if (objectKey == null || !objectKey.matches("[A-Z]+/[a-f0-9]{64}/[a-zA-Z0-9._-]+"))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Original document is unavailable");
        try {
            if (!cloud) return Files.readAllBytes(resolve(objectKey));
            HttpRequest request = HttpRequest.newBuilder(URI.create(url + "/storage/v1/object/authenticated/" + bucket + "/" + objectKey))
                    .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + key).header("apikey", key).GET().build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) throw new IllegalStateException("Storage rejected download");
            return response.body();
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Download interrupted"); }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Original document is unavailable"); }
    }
    private Path resolve(String objectKey) {
        Path destination = root.resolve(objectKey).normalize();
        if (!destination.startsWith(root)) throw new IllegalArgumentException("Invalid document path");
        return destination;
    }
}
