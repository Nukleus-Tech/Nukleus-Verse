package com.nukleus.vrmeeting.accountdeletion.phase3;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nukleus.vrmeeting.accountdeletion.*;
import com.nukleus.vrmeeting.accountdeletion.phase2.Phase2Ports.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.net.URI;
import java.util.*;

/** Uses only documented Meshy task APIs and server-reviewed task ownership, never arbitrary URLs. */
public final class MeshyTaskCleanup implements ExternalReferenceClient {
    private final JdbcTemplate jdbc;
    private final CleanupIntegrationProperties properties;
    private final OperationFence fence;
    private final ProviderHttp http;
    private final ObjectMapper json;
    private final VerifiedProviderAssets assets;
    public MeshyTaskCleanup(JdbcTemplate jdbc,CleanupIntegrationProperties properties,OperationFence fence,ProviderHttp http,ObjectMapper json,
            VerifiedProviderAssets assets){
        this.jdbc=jdbc;this.properties=properties;this.fence=fence;this.http=http;this.json=json;this.assets=assets;
    }
    @Override public boolean hasAdditionalReferences(long userId){
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from account_deletion_provider_tasks where user_id=?)",
            Boolean.class,userId));
    }
    private List<Map<String,Object>> tasks(long userId,String current){
        if(Boolean.TRUE.equals(jdbc.queryForObject("""
            select exists(select 1 from account_deletion_provider_tasks t join users u
                on lower(btrim(u.meshy_task_id))=t.task_id::text where t.user_id=? and u.id<>?)
            """,Boolean.class,userId,userId)))throw AccountDeletionException.integration();
        var tasks=jdbc.queryForList("""
            select * from account_deletion_provider_tasks where user_id=?
            order by case kind when 'animations' then 0 when 'rigging' then 1 when 'image-to-3d' then 2 else 3 end,task_id
            """,userId);
        if(current!=null && !current.isBlank() && tasks.stream().noneMatch(t->current.equals(t.get("task_id").toString())))
            throw AccountDeletionException.integration();
        if(tasks.isEmpty())throw AccountDeletionException.integration();
        return tasks;
    }
    private URI endpoint(Map<String,Object> task){
        String kind=task.get("kind").toString();
        if(!Set.of("image-to-image","image-to-3d","rigging","animations").contains(kind))throw AccountDeletionException.integration();
        String id=UUID.fromString(task.get("task_id").toString()).toString();
        return URI.create("https://api.meshy.ai/openapi/v1/"+kind+"/"+id);
    }
    private Map<String,String> headers(){
        if(!properties.isProviderCallsEnabled() || properties.getMeshyApiKey().isBlank())throw AccountDeletionException.integration();
        return Map.of("Authorization","Bearer "+properties.getMeshyApiKey());
    }
    @Override public void verifyRemovable(long userId,String reference){
        fence.requireHeld(true);
        for(var task:tasks(userId,reference)){
            if(task.get("deleted_at")!=null)continue;
            var result=http.exchange("GET",endpoint(task),headers(),null);
            if(result.status()==404){
                if(task.get("verified_at")==null)throw AccountDeletionException.integration();
                continue; // Only previously observed, proved-owned tasks may be treated as already absent.
            }
            ProviderHttp.requireSuccess(result.status());
            try{
                var body=json.readTree(result.body());
                if(!task.get("task_id").toString().equals(body.path("id").asText()))throw AccountDeletionException.integration();
                String status=body.path("status").asText();
                if("IN_PROGRESS".equals(status))throw new RetryableIntegrationFailure();
                if(!Set.of("PENDING","SUCCEEDED","FAILED","CANCELED","CANCELLED").contains(status))throw AccountDeletionException.integration();
                assets.record((UUID)task.get("task_id"),body);
            }catch(java.io.IOException e){throw AccountDeletionException.integration();}
            jdbc.update("update account_deletion_provider_tasks set verified_at=coalesce(verified_at,now()) where user_id=? and task_id=?",
                userId,task.get("task_id"));
        }
        assets.requireUnshared(userId);
    }
    @Override public void removeApplicationReference(long userId,String reference,UUID planId){
        verifyRemovable(userId,reference);
        for(var task:tasks(userId,reference)){
            if(task.get("deleted_at")!=null)continue;
            var result=http.exchange("DELETE",endpoint(task),headers(),null);
            if(result.status()!=404)ProviderHttp.requireSuccess(result.status());
            else if(task.get("verified_at")==null)throw AccountDeletionException.integration();
            jdbc.update("update account_deletion_provider_tasks set deleted_at=coalesce(deleted_at,now()) where user_id=? and task_id=?",
                userId,task.get("task_id"));
        }
    }
}
