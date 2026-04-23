package com.inboxintelligence.processor.domain.clustering;

import com.inboxintelligence.persistence.model.ClusterAssignmentType;
import com.inboxintelligence.persistence.model.entity.Cluster;
import com.inboxintelligence.persistence.model.entity.EmailEnrichment;
import com.inboxintelligence.persistence.service.ClusterService;
import com.inboxintelligence.persistence.service.EmailContentService;
import com.inboxintelligence.persistence.service.EmailEnrichmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

import static com.inboxintelligence.persistence.model.ProcessedStatus.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailClusteringService {

    private final BatchClusteringLock batchClusteringMonitor;
    private final EmailContentService emailContentService;
    private final EmailEnrichmentService emailEnrichmentService;
    private final ClusterService clusterService;

    public void assignCluster(Long emailContentId) {

        var emailContent = emailContentService
                .findById(emailContentId)
                .orElseThrow(() -> new IllegalStateException("EmailContent not found: " + emailContentId));

        try {

            emailContentService.updateStatusAndNote(emailContent, CLUSTER_ASSIGNMENT_STARTED, null);

            if (batchClusteringMonitor.isActive()) {
                log.info("Batch clustering in process — skipping incremental assignment for emailContent [id={}]", emailContentId);
                emailContentService.updateStatusAndNote(emailContent, CLUSTER_ASSIGNMENT_PAUSED, null);
                return;
            }

            List<Cluster> clusters = clusterService.findByMailboxId(emailContent.getGmailMailboxId());
            if (clusters.isEmpty()) {
                log.info("No clusters for mailbox [id={}] — skipping incremental assignment", emailContent.getGmailMailboxId());
                emailContentService.updateStatusAndNote(emailContent, CLUSTER_ASSIGNMENT_PAUSED, null);
                return;
            }

            EmailEnrichment enrichment = emailEnrichmentService
                    .findByEmailContentId(emailContentId)
                    .orElseThrow(() -> new IllegalStateException("EmailEnrichment not found for emailContent: " + emailContentId));

            if (enrichment.getEmbedding() == null) {
                log.warn("No embedding on emailContent [id={}] — skipping incremental assignment", emailContentId);
                emailContentService.updateStatusAndNote(emailContent, PROCESSING_FAILED, "No embedding found for emailContent");
                return;
            }

            Cluster bestCluster = findBestCluster(enrichment.getEmbedding(), clusters);
            if (bestCluster == null) {
                log.warn("No cluster with a centroid found for mailbox [id={}]", emailContent.getGmailMailboxId());
                emailContentService.updateStatusAndNote(emailContent, PROCESSING_FAILED, "No cluster centroid found");
                return;
            }

            double similarity = cosineSimilarity(enrichment.getEmbedding(), bestCluster.getCentroid());

            enrichment.setClusterId(bestCluster.getId());
            enrichment.setClusterProbability(similarity);
            enrichment.setClusterAssignmentType(ClusterAssignmentType.INCREMENTAL);
            emailEnrichmentService.save(enrichment);

            bestCluster.setEmailCount(Objects.requireNonNullElse(bestCluster.getEmailCount(), 0) + 1);
            clusterService.save(bestCluster);

            emailContentService.updateStatusAndNote(emailContent, CLUSTER_ASSIGNMENT_COMPLETED, null);
            log.info("EmailContent [id={}] assigned to cluster [id={}, label={}, similarity={}]", emailContentId, bestCluster.getId(), bestCluster.getClusterLabel(), similarity);

        } catch (Exception e) {
            log.error("Failed to assign cluster for emailContent [id={}]", emailContentId, e);
            emailContentService.updateStatusAndNote(emailContent, PROCESSING_FAILED, e.getMessage());
            throw e;
        }
    }

    private Cluster findBestCluster(float[] embedding, List<Cluster> clusters) {
        Cluster best = null;
        double bestSimilarity = Double.NEGATIVE_INFINITY;

        for (Cluster cluster : clusters) {
            if (cluster.getCentroid() == null) {
                continue;
            }
            double similarity = cosineSimilarity(embedding, cluster.getCentroid());
            if (similarity > bestSimilarity) {
                bestSimilarity = similarity;
                best = cluster;
            }
        }
        return best;
    }

    private double cosineSimilarity(float[] a, float[] b) {
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot  += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
