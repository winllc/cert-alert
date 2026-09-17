package com.winllc.certalert.repository;

/**
 * The little of an entry the prune job needs: enough to say in the audit trail what was
 * deleted, without loading a hundred thousand entities to delete them in bulk.
 */
public interface PrunableEntry {

    Long getId();

    String getDn();

    String getName();
}
