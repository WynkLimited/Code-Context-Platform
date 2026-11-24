package com.wynk.codecontext.port.contract.compat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.wynk.codecontext.port.contract.ContractModels.ContractIdentity;
import com.wynk.codecontext.port.model.ProductModels.FindingProvenance;

/** Result types for deterministic contract compatibility analysis. */
public final class CompatibilityModels {

    private CompatibilityModels() {}

    /** The specific kinds of change the protobuf/gRPC rules distinguish. */
    public enum ContractChangeType {
        CONTRACT_ADDED,
        CONTRACT_REMOVED,
        MESSAGE_RENAMED,
        PACKAGE_CHANGED,
        SERVICE_RENAMED,
        FIELD_ADDED,
        FIELD_REMOVED,
        FIELD_NUMBER_CHANGED,
        FIELD_TYPE_CHANGED,
        FIELD_CARDINALITY_CHANGED,
        ENUM_VALUE_ADDED,
        ENUM_VALUE_REMOVED,
        RPC_ADDED,
        RPC_REMOVED,
        RPC_REQUEST_CHANGED,
        RPC_RESPONSE_CHANGED,
        API_SIGNATURE_CHANGED,
        BUILD_DEPENDENCY_CHANGED,
        SCHEMA_CHANGED,
        ENDPOINT_SIGNATURE_CHANGED,
        /** Public API signature unchanged; method/type body changed (Gnostic Phase B). */
        IMPLEMENTATION_BODY_CHANGED
    }

    /**
     * Compatibility verdict for one change.
     *
     * <p>Only {@link #BREAKING} is deterministic evidence that may become a blocking finding.
     * {@link #UNKNOWN} exists so a change the rules do not model is visible rather than silently
     * classified as safe — it degrades to advisory.
     */
    public enum Compatibility {
        /** Existing consumers keep working against the new producer. */
        BACKWARD_COMPATIBLE,
        /** New consumers keep working against the old producer. */
        FORWARD_COMPATIBLE,
        /** At least one existing consumer is broken by this change. */
        BREAKING,
        /** Not modelled by the current rules; never blocking. */
        UNKNOWN;

        public boolean isDeterministicBreak() {
            return this == BREAKING;
        }
    }

    /**
     * One classified difference between a contract's baseline and head definition.
     *
     * @param identity      the contract this change belongs to
     * @param changeType    what changed
     * @param compatibility the verdict from the rules
     * @param beforeHash    content hash at the PR base commit, null when newly added
     * @param afterHash     content hash at the PR head commit, null when removed
     * @param beforeSignature normalized baseline signature, for evidence in findings and prompts
     * @param afterSignature  normalized head signature
     * @param rulesVersion  version of the rules that produced {@code compatibility}
     * @param detail        human-readable specifics, e.g. which field moved number
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ContractChange(
            ContractIdentity identity,
            ContractChangeType changeType,
            Compatibility compatibility,
            String beforeHash,
            String afterHash,
            String beforeSignature,
            String afterSignature,
            String rulesVersion,
            String detail
    ) {
        public boolean isBreaking() {
            return compatibility.isDeterministicBreak();
        }

        public String contractKey() {
            return identity.canonicalKey();
        }

        /**
         * Provenance a finding derived from this change may claim. A breaking change to a
         * service or RPC signature is a signature break; any other breaking change is contract
         * drift. Non-breaking changes carry no deterministic provenance.
         */
        public FindingProvenance provenance() {
            if (!isBreaking()) {
                return FindingProvenance.INFERRED;
            }
            return switch (changeType) {
                case RPC_REMOVED, RPC_REQUEST_CHANGED, RPC_RESPONSE_CHANGED, SERVICE_RENAMED,
                        API_SIGNATURE_CHANGED, ENDPOINT_SIGNATURE_CHANGED ->
                        FindingProvenance.SIGNATURE_BREAK;
                default -> FindingProvenance.CONTRACT_DRIFT;
            };
        }
    }
}
