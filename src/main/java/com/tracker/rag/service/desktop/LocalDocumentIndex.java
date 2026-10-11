package com.tracker.rag.service.desktop;

import com.tracker.rag.domain.DomainScope;
import com.tracker.rag.dto.Citation;
import com.tracker.rag.repository.DocumentRecordRepository;
import com.tracker.rag.repository.vector.*;
import com.tracker.rag.service.ai.*;
import com.tracker.rag.service.storage.DocumentStorageService;
import com.tracker.rag.service.retrieval.InvertedIndexServiceImpl;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Separate, model-identified index of stored originals; legacy vectors remain untouched. */
@Service @Profile("desktop")
public class LocalDocumentIndex {
    private final JdbcTemplate jdbc;private final DocumentRecordRepository documents;private final DocumentStorageService storage;
    private final AiClient ai;private final TransactionTemplate transaction;
    public LocalDocumentIndex(JdbcTemplate jdbc,DocumentRecordRepository documents,DocumentStorageService storage,AiClient ai,PlatformTransactionManager transactions){
        this.jdbc=jdbc;this.documents=documents;this.storage=storage;this.ai=ai;this.transaction=new TransactionTemplate(transactions);
    }
    private record Piece(int page,String heading,String text) {}
    private void split(List<Piece> output,int page,String heading,String text){
        String body=text.strip();
        for(int start=0;start<body.length();){
            int end=Math.min(body.length(),start+1200);
            if(end<body.length()){int boundary=body.lastIndexOf('\n',end);if(boundary>start+600)end=boundary;}
            String chunk=body.substring(start,end).strip();if(!chunk.isBlank())output.add(new Piece(page,heading,chunk));
            if(output.size()>512)throw new IllegalArgumentException("This document exceeds the local index limit. Split it into smaller files.");
            if(end==body.length())break;start=Math.max(start+1,end-120);
        }
    }
    public Map<String,Object> index(UUID id,DomainScope domain,LocalAiJobs.Scope job) throws Exception {
        DomainScope.validateScope(domain);job.check();
        var document=documents.findByIdAndDomain(id,domain).orElseThrow(()->new IllegalArgumentException("Document not found in this area"));
        if(document.getStoragePath()==null)throw new IllegalArgumentException("Re-upload this document's original file before indexing");
        byte[] bytes=storage.read(document.getStoragePath());
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        if(!hash.equals(document.getContentHash()))throw new IllegalArgumentException("Original file checksum changed. Re-upload before indexing.");
        String space=ai.embeddingSpace();
        if(space.equals("ai-postponed"))throw new AiServiceException(503,"Local AI assets are unavailable.");
        List<Piece> pieces=new ArrayList<>();
        if(document.getContentType().equals("application/pdf")){
            try(var pdf=Loader.loadPDF(bytes)){
                if(pdf.getNumberOfPages()>1000)throw new IllegalArgumentException("Too many PDF pages");
                var extractor=new PDFTextStripper();
                for(int page=1;page<=pdf.getNumberOfPages();page++){job.check();extractor.setStartPage(page);extractor.setEndPage(page);split(pieces,page,null,extractor.getText(pdf));}
            }
        }else{
            StringBuilder section=new StringBuilder();String heading=null;
            for(String line:new String(bytes,StandardCharsets.UTF_8).split("\\R")){
                if(line.matches("#{1,6}\\s+.+")){split(pieces,0,heading,section.toString());section.setLength(0);heading=line.replaceFirst("^#{1,6}\\s+","");if(heading.length()>1024)heading=heading.substring(0,1024);}
                section.append(line).append('\n');
            }
            split(pieces,0,heading,section.toString());
        }
        if(pieces.isEmpty())throw new IllegalArgumentException("No readable text was found in this document");
        List<float[]> vectors=new ArrayList<>();
        for(Piece piece:pieces){job.check();float[] vector=ai.generateEmbedding(piece.text(),domain);if(vector.length!=768)throw new AiServiceException(502,"Invalid local embedding dimensions");double norm=0;for(float value:vector){if(!Float.isFinite(value))throw new AiServiceException(502,"Invalid local embedding values");norm+=(double)value*value;}if(norm<=0||!Double.isFinite(norm))throw new AiServiceException(502,"Invalid local embedding values");double length=Math.sqrt(norm);for(int i=0;i<vector.length;i++)vector[i]=(float)(vector[i]/length);vectors.add(vector);}
        job.check();
        transaction.executeWithoutResult(status->{
            job.check();
            var current=jdbc.queryForList("SELECT content_hash FROM document_records WHERE id=? AND domain=? FOR UPDATE",id,domain.name());
            if(current.isEmpty()||!hash.equals(current.getFirst().get("content_hash")))LocalDataService.conflict();
            // Replace only this document in this new embedding namespace, atomically.
            jdbc.update("DELETE FROM local_ai_indexes WHERE document_id=? AND embedding_space=?",id,space);
            jdbc.update("INSERT INTO local_ai_indexes(document_id,embedding_space,content_hash,chunk_count,indexed_at) VALUES(?,?,?,?,?)",id,space,hash,pieces.size(),Instant.now());
            for(int i=0;i<pieces.size();i++){job.check();var piece=pieces.get(i);jdbc.update("INSERT INTO local_ai_chunks(id,document_id,embedding_space,domain,page_number,heading_path,content,embedding_bytes) VALUES(?,?,?,?,?,?,?,?)",UUID.randomUUID(),id,space,domain.name(),piece.page(),piece.heading(),piece.text(),VectorSerializationUtils.floatsToBytes(vectors.get(i)));}
            job.check();
        });
        return Map.of("documentId",id,"chunks",pieces.size(),"embeddingSpace",space);
    }
    public List<Map<String,Object>> status(String area){
        var scopes=LocalDataService.scopes(area);String placeholders=String.join(",",Collections.nCopies(scopes.size(),"?"));
        List<Object> args=new ArrayList<>();args.add(ai.embeddingSpace());args.addAll(scopes);
        return jdbc.query("SELECT d.id AS document_id, i.chunk_count AS chunks,i.indexed_at AS indexed_at FROM local_ai_indexes i JOIN document_records d ON d.id=i.document_id AND d.content_hash=i.content_hash WHERE i.embedding_space=? AND d.domain IN ("+placeholders+")",
                (rs,row)->Map.<String,Object>of("documentId",rs.getObject("document_id"),"chunks",rs.getInt("chunks"),"indexedAt",rs.getObject("indexed_at").toString()),args.toArray());
    }
    public List<Citation> search(String query,String area,LocalAiJobs.Scope job){
        if(query==null||query.isBlank()||query.length()>2000)throw new IllegalArgumentException("Enter a question of at most 2,000 characters");
        var scopes=LocalDataService.scopes(area);String placeholders=String.join(",",Collections.nCopies(scopes.size(),"?"));
        List<Object> args=new ArrayList<>();args.add(ai.embeddingSpace());args.addAll(scopes);
        List<VectorChunk> chunks=jdbc.query("SELECT c.*,d.file_name FROM local_ai_chunks c JOIN local_ai_indexes i ON i.document_id=c.document_id AND i.embedding_space=c.embedding_space JOIN document_records d ON d.id=c.document_id AND d.content_hash=i.content_hash AND d.domain=c.domain WHERE c.embedding_space=? AND c.domain IN ("+placeholders+") LIMIT 10001",
                (rs,row)->new VectorChunk((UUID)rs.getObject("id"),rs.getString("file_name"),DomainScope.parse(rs.getString("domain")),rs.getInt("page_number"),rs.getString("heading_path"),rs.getString("content"),VectorSerializationUtils.bytesToFloats(rs.getBytes("embedding_bytes"))),args.toArray());
        if(chunks.size()>10000)throw new IllegalArgumentException("This index is too large for the beta search limit");
        if(chunks.isEmpty())return List.of();
        var sparse=new InvertedIndexServiceImpl();sparse.indexChunks(chunks);
        Map<UUID,Double> scores=new HashMap<>();Map<UUID,Integer> denseRanks=new HashMap<>(),sparseRanks=new HashMap<>();
        for(String scope:scopes){job.check();DomainScope domain=DomainScope.parse(scope);
            float[] vector=ai.generateQueryEmbedding(query,domain);
            Map<UUID,Double> similarities=new HashMap<>();for(var c:chunks)if(c.domain()==domain)similarities.put(c.id(),VectorSerializationUtils.cosineSimilarity(vector,c.embedding()));
            var dense=chunks.stream().filter(c->c.domain()==domain).sorted(Comparator.comparingDouble((VectorChunk c)->similarities.get(c.id())).reversed()).limit(20).toList();
            for(int i=0;i<dense.size();i++){var c=dense.get(i);if(!Double.isFinite(similarities.get(c.id()))||similarities.get(c.id())<0.15)continue;scores.merge(c.id(),1.0/(60+i+1),Double::sum);denseRanks.put(c.id(),i+1);}
            var postings=sparse.search(query,domain,20);for(int i=0;i<postings.size();i++){var posting=postings.get(i);scores.merge(posting.chunkId(),1.0/(60+i+1),Double::sum);sparseRanks.put(posting.chunkId(),i+1);}
        }
        job.check();
        return chunks.stream().filter(c->scores.containsKey(c.id())).sorted(Comparator.comparingDouble((VectorChunk c)->scores.get(c.id())).reversed()).limit(5)
                .map(c->new Citation(c.documentId(),c.pageNumber()==0?null:c.pageNumber(),c.headingPath(),c.content(),scores.get(c.id()),denseRanks.getOrDefault(c.id(),0),sparseRanks.getOrDefault(c.id(),0))).toList();
    }
}
