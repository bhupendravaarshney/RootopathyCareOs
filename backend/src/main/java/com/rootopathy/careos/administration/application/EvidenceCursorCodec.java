package com.rootopathy.careos.administration.application;
import java.time.Instant;import java.util.UUID;
public interface EvidenceCursorCodec{String encode(Binding binding,Position position);Position decode(String cursor,Binding binding);record Binding(UUID organizationId,String projection,String filterDigest,int limit){}record Position(Instant asOf,Instant occurredAt,UUID id){} }
