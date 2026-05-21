package com.inboxintelligence.processor.domain.clustering;

import com.inboxintelligence.persistence.model.ClusterAssignmentType;
import com.inboxintelligence.persistence.model.entity.Cluster;
import com.inboxintelligence.persistence.model.entity.EmailContent;
import com.inboxintelligence.persistence.model.entity.EmailEnrichment;
import com.inboxintelligence.persistence.service.ClusterService;
import com.inboxintelligence.persistence.service.EmailContentService;
import com.inboxintelligence.persistence.service.EmailEnrichmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import static com.inboxintelligence.persistence.model.ProcessedStatus.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailClusteringService {

    @Value("${clustering.incremental.min-similarity-threshold}")
    private double minSimilarityThreshold;

    private final BatchClusteringLock batchClusteringLock;
    private final EmailContentService emailContentService;
    private final EmailEnrichmentService emailEnrichmentService;
    private final ClusterService clusterService;

    public void assignCluster(Long emailContentId) {

        log.info("Assigning cluster for emailContent id: {}", emailContentId);

        EmailContent emailContent = emailContentService
                .findById(emailContentId)
                .orElseThrow(() -> new IllegalStateException("EmailContent not found: " + emailContentId));

        EmailEnrichment enrichment = emailEnrichmentService
                .findByEmailContentId(emailContentId)
                .orElseGet(EmailEnrichment::new);

        if (enrichment.getClusterId() != null) {
            log.warn("EmailContent [id={}] already cluster-assigned (status={}) — skipping redelivery", emailContentId, emailContent.getProcessedStatus());
            emailContentService.updateStatusAndNote(emailContent, CLUSTER_ASSIGNMENT_COMPLETED, null);
            return;
        }
        invokeIncrementalClustering(emailContent, enrichment);
    }

    private void invokeIncrementalClustering(EmailContent emailContent, EmailEnrichment enrichment) {

        try {
            emailContentService.updateStatusAndNote(emailContent, CLUSTER_ASSIGNMENT_STARTED, null);

            if (batchClusteringLock.isActive(emailContent.getGmailMailboxId())) {
                log.info("Batch clustering active for mailbox [id={}] — pausing incremental assignment for emailContent [id={}]", emailContent.getGmailMailboxId(), emailContent.getId());
                emailContentService.updateStatusAndNote(emailContent, CLUSTER_ASSIGNMENT_COMPLETED, null);
                return;
            }

            List<Cluster> clusters = clusterService.findByMailboxId(emailContent.getGmailMailboxId());
            if (clusters.isEmpty()) {
                log.info("No clusters for mailbox [id={}] — deferring assignment for emailContent [id={}]", emailContent.getGmailMailboxId(), emailContent.getId());
                emailContentService.updateStatusAndNote(emailContent, CLUSTER_ASSIGNMENT_COMPLETED, null);
                return;
            }

            if (enrichment.getEmbedding() == null) {
                log.warn("No embedding on emailContent [id={}] — skipping incremental assignment", emailContent.getId());
                emailContentService.updateStatusAndNote(emailContent, CLUSTER_ASSIGNMENT_FAILED, "No embedding found");
                return;
            }

            Cluster bestCluster = findBestCluster(enrichment.getEmbedding(), clusters);
            if (bestCluster == null) {
                log.warn("No cluster with a centroid found for mailbox [id={}]", emailContent.getGmailMailboxId());
                emailContentService.updateStatusAndNote(emailContent, CLUSTER_ASSIGNMENT_FAILED, "No cluster centroid found");
                return;
            }

            double similarity = cosineSimilarity(enrichment.getEmbedding(), bestCluster.getCentroid());

            if (similarity < minSimilarityThreshold) {
                log.info("EmailContent [id={}] similarity {} below threshold {} — deferring to batch", emailContent.getId(), String.format("%.4f", similarity), String.format("%.4f", minSimilarityThreshold));
                emailContentService.updateStatusAndNote(emailContent, CLUSTER_ASSIGNMENT_COMPLETED, "similarity=" + String.format("%.4f", similarity));
                return;
            }

            enrichment.setClusterId(bestCluster.getId());
            enrichment.setClusterProbability(similarity);
            enrichment.setClusterAssignmentType(ClusterAssignmentType.INCREMENTAL);
            emailEnrichmentService.save(enrichment);

            clusterService.incrementEmailCount(bestCluster.getId());

            emailContentService.updateStatusAndNote(emailContent, CLUSTER_ASSIGNMENT_COMPLETED, null);
            log.info("EmailContent [id={}] assigned to cluster [id={}, clusterIndex={}, similarity={}]", emailContent.getId(), bestCluster.getId(), bestCluster.getClusterIndex(), similarity);

        } catch (Exception e) {
            log.error("Failed to assign cluster for emailContent [id={}]", emailContent.getId(), e);
            emailContentService.updateStatusAndNote(emailContent, CLUSTER_ASSIGNMENT_FAILED, e.getMessage());
            throw e;
        }
    }

    private Cluster findBestCluster(float[] embedding, List<Cluster> clusters) {
        Cluster best = null;
        double bestSimilarity = Double.NEGATIVE_INFINITY;

        for (Cluster cluster : clusters) {
            if (cluster.getCentroid() == null) continue;
            double similarity = cosineSimilarity(embedding, cluster.getCentroid());
            if (similarity > bestSimilarity) {
                bestSimilarity = similarity;
                best = cluster;
            }
        }
        return best;
    }

    private double cosineSimilarity(float[] a, float[] b) {
        double dot = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot  += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0.0 || normB == 0.0) return 0.0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
