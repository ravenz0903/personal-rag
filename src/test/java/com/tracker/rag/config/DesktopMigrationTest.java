package com.tracker.rag.config;
import org.flywaydb.core.Flyway;
import org.h2.tools.RunScript;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import java.nio.file.*;
import java.sql.*;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class DesktopMigrationTest {
    @TempDir Path directory;
    @Test void existingRecordsVectorsAndDeliveryHistorySurviveAndRerunIsSafe()throws Exception{
        String url="jdbc:h2:file:"+directory.resolve("tracker").toString().replace('\\','/')+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE";
        UUID task=UUID.randomUUID(),vector=UUID.randomUUID();byte[] embedding={1,2,3,4};
        try(Connection c=DriverManager.getConnection(url,"sa","");var script=new InputStreamReader(getClass().getResourceAsStream("/db/desktop/V1__existing_workspace.sql"),java.nio.charset.StandardCharsets.UTF_8)){
            RunScript.execute(c,script);
            try(var s=c.prepareStatement("INSERT INTO tasks(id,domain,title,priority,status,created_at,updated_at,due_date) VALUES(?,'ACADEMICS','Preserved','MEDIUM','PENDING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,TIMESTAMP WITH TIME ZONE '2028-02-29 09:00:00+05:30')")){s.setObject(1,task);s.executeUpdate();}
            try(var s=c.prepareStatement("INSERT INTO vector_chunks(id,document_id,domain,page_number,content,embedding_bytes) VALUES(?,'old.md','ACADEMICS',1,'Preserve my embedding',?)")){s.setObject(1,vector);s.setBytes(2,embedding);s.executeUpdate();}
        }
        var migration=new DesktopMigrations().safeMigration(new MockEnvironment().withProperty("spring.datasource.url",url));
        var flyway=Flyway.configure().dataSource(url,"sa","").locations("classpath:db/desktop").baselineOnMigrate(true).baselineVersion("1").load();migration.migrate(flyway);
        try(Connection c=DriverManager.getConnection(url,"sa","");var s=c.createStatement()){
            try(var rows=s.executeQuery("SELECT id,title,due_date,version FROM tasks")){assertTrue(rows.next());assertEquals(task,rows.getObject(1));assertEquals("Preserved",rows.getString(2));assertEquals("2028-02-29",rows.getObject(3,java.time.OffsetDateTime.class).toLocalDate().toString());assertEquals(0,rows.getLong(4));}
            try(var rows=s.executeQuery("SELECT embedding_bytes FROM vector_chunks")){assertTrue(rows.next());assertArrayEquals(embedding,rows.getBytes(1));}
        }
        assertTrue(Files.exists(directory.resolve("migration-backups")));long count;try(var files=Files.list(directory.resolve("migration-backups"))){count=files.count();}migration.migrate(flyway);try(var files=Files.list(directory.resolve("migration-backups"))){assertEquals(count,files.count());}
    }
}
