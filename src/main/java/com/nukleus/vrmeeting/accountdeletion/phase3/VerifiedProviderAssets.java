package com.nukleus.vrmeeting.accountdeletion.phase3;

import com.fasterxml.jackson.databind.JsonNode;
import com.nukleus.vrmeeting.accountdeletion.AccountDeletionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import java.net.URI;
import java.util.*;

/** Output evidence obtained from authenticated task GETs, not from a client URL claim. */
public final class VerifiedProviderAssets {
    private final JdbcTemplate jdbc;
    private final OperationFence fence;
    public VerifiedProviderAssets(JdbcTemplate jdbc,OperationFence fence){this.jdbc=jdbc;this.fence=fence;}
    public static Optional<String> locatorHash(String url){
        try{
            URI uri=URI.create(url);
            if(!"https".equalsIgnoreCase(uri.getScheme()) || !"assets.meshy.ai".equalsIgnoreCase(uri.getHost()) ||
                uri.getPort()!=-1 || uri.getUserInfo()!=null || uri.getFragment()!=null) return Optional.empty();
            String path=uri.getPath();
            if(path==null || path.contains("\\") || path.contains("..") || path.contains("//") ||
                path.chars().anyMatch(c->c<32 || c==127)) return Optional.empty();
            // Meshy documents expiring signatures on the same provider-controlled output path.
            return Optional.of(JdbcSessionAuthority.digest("https://assets.meshy.ai"+path));
        }catch(IllegalArgumentException|NullPointerException e){return Optional.empty();}
    }
    public void record(UUID taskId,JsonNode response){
        fence.requireHeld(true);
        Set<String> hashes=new HashSet<>();
        for(String field:List.of("image_urls","model_urls","texture_urls","thumbnail_url","thumbnail_urls","result"))
            collect(response.path(field),hashes);
        for(String hash:hashes)jdbc.update("""
            insert into account_deletion_provider_assets(task_id,url_hash) values(?,?) on conflict do nothing
            """,taskId,hash);
    }
    private void collect(JsonNode value,Set<String> hashes){
        if(value.isTextual())locatorHash(value.asText()).ifPresent(hashes::add);
        else if(value.isContainerNode())value.forEach(child->collect(child,hashes));
    }
    public boolean approved(long userId,String url){
        var hash=locatorHash(url);
        return hash.isPresent() && Boolean.TRUE.equals(jdbc.queryForObject("""
            select exists(select 1 from account_deletion_provider_assets a
                join account_deletion_provider_tasks t on t.task_id=a.task_id
                where t.user_id=? and a.url_hash=? and t.verified_at is not null)
            """,Boolean.class,userId,hash.get()));
    }
    public void requireUnshared(long userId){
        fence.requireHeld(true);
        Set<String> hashes=new HashSet<>(jdbc.queryForList("""
            select a.url_hash from account_deletion_provider_assets a
                join account_deletion_provider_tasks t on t.task_id=a.task_id where t.user_id=?
            """,String.class,userId));
        if(hashes.isEmpty())return;
        RowCallbackHandler check=row->{
            if(locatorHash(row.getString(1)).filter(hashes::contains).isPresent())
                throw new AccountDeletionException(org.springframework.http.HttpStatus.CONFLICT,"STORAGE_OWNERSHIP_UNVERIFIED");
        };
        jdbc.query("""
            select image_url from users where id<>?
            union all select avatar_url from users where id<>?
            union all select rigged_glb_url from users where id<>?
            union all select walking_glb_url from users where id<>?
            union all select idle_glb_url from users where id<>?
            union all select sitting_glb_url from users where id<>?
            union all select recording_url from meetings union all select pdf_url from meetings
            union all select notes_url from meetings union all select ppt_url from meetings
            """,check,userId,userId,userId,userId,userId,userId);
    }
}
