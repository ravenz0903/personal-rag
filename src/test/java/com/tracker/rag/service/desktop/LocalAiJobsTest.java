package com.tracker.rag.service.desktop;
import com.tracker.rag.service.ai.AiServiceException;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class LocalAiJobsTest {
    @Test void cancelsRunningJobAndClearsRequestInterruptWithoutAffectingLaterWork() throws Exception {
        var jobs=new LocalAiJobs();UUID id=UUID.randomUUID();var entered=new CountDownLatch(1);
        try(var worker=Executors.newSingleThreadExecutor()){
            var result=worker.submit(()->{
                try(var scope=jobs.begin(id)){
                    entered.countDown();try{Thread.sleep(10000);}catch(InterruptedException error){Thread.currentThread().interrupt();}
                    assertEquals(408,assertThrows(AiServiceException.class,scope::check).status());
                }
                assertFalse(Thread.currentThread().isInterrupted());
                try(var next=jobs.begin(UUID.randomUUID())){next.check();}return true;
            });
            assertTrue(entered.await(2,TimeUnit.SECONDS));jobs.cancel(id);assertTrue(result.get(2,TimeUnit.SECONDS));
            jobs.cancel(id);assertTrue(worker.submit(()->!Thread.currentThread().isInterrupted()).get());
        }
    }
    @Test void cancellationBeforeRegistrationAndConcurrentDuplicateIdsAreRejected(){
        var jobs=new LocalAiJobs();UUID cancelled=UUID.randomUUID();jobs.cancel(cancelled);
        assertEquals(408,assertThrows(AiServiceException.class,()->jobs.begin(cancelled)).status());
        UUID id=UUID.randomUUID();try(var scope=jobs.begin(id)){assertEquals(409,assertThrows(AiServiceException.class,()->jobs.begin(id)).status());}
    }
}
