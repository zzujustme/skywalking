/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.skywalking.oap.server.receiver.zipkin.trace;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.skywalking.oap.server.core.Const;
import org.apache.skywalking.oap.server.core.CoreModule;
import org.apache.skywalking.oap.server.core.analysis.manual.searchtag.Tag;
import org.apache.skywalking.oap.server.core.analysis.manual.searchtag.TagType;
import org.apache.skywalking.oap.server.core.source.TagAutocomplete;
import org.apache.skywalking.oap.server.core.zipkin.ZipkinSpanRecord;
import org.apache.skywalking.oap.server.core.zipkin.source.ZipkinService;
import org.apache.skywalking.oap.server.core.zipkin.source.ZipkinServiceRelation;
import org.apache.skywalking.oap.server.core.zipkin.source.ZipkinServiceSpan;
import org.apache.skywalking.oap.server.core.zipkin.source.ZipkinSpan;
import org.apache.skywalking.oap.server.core.analysis.TimeBucket;
import org.apache.skywalking.oap.server.core.config.NamingControl;
import org.apache.skywalking.oap.server.core.source.SourceReceiver;
import org.apache.skywalking.oap.server.library.module.ModuleManager;
import org.apache.skywalking.oap.server.library.util.CollectionUtils;
import org.apache.skywalking.oap.server.library.util.StringUtil;
import org.apache.skywalking.oap.server.receiver.zipkin.ZipkinReceiverConfig;
import zipkin2.Annotation;
import zipkin2.Span;
import zipkin2.internal.HexCodec;

@Slf4j
public class SpanForward {
    private final ZipkinReceiverConfig config;
    private final NamingControl namingControl;
    private final SourceReceiver receiver;
    private final List<String> searchTagKeys;
    private final long samplerBoundary;

    public SpanForward(final ZipkinReceiverConfig config, final ModuleManager manager) {
        this.config = config;
        this.namingControl = manager.find(CoreModule.NAME).provider().getService(NamingControl.class);
        this.receiver = manager.find(CoreModule.NAME).provider().getService(SourceReceiver.class);
        this.searchTagKeys = Arrays.asList(config.getSearchableTracesTags().split(Const.COMMA));
        float sampleRate = (float) config.getSampleRate() / 10000;
        samplerBoundary = (long) (Long.MAX_VALUE * sampleRate);
    }

    public void send(List<Span> spanList) {
        if (CollectionUtils.isEmpty(spanList)) {
            return;
        }
        getSampledTraces(spanList).forEach(span -> {
            ZipkinSpan zipkinSpan = new ZipkinSpan();
            String serviceName = span.localServiceName();
            if (StringUtil.isEmpty(serviceName)) {
                serviceName = "Unknown";
            }
            zipkinSpan.setSpanId(span.id());
            zipkinSpan.setTraceId(span.traceId());
            zipkinSpan.setSpanId(span.id());
            zipkinSpan.setParentId(span.parentId());
            zipkinSpan.setName(namingControl.formatEndpointName(serviceName, span.name()));
            zipkinSpan.setDuration(span.duration());
            if (span.kind() != null) {
                zipkinSpan.setKind(span.kind().name());
            }
            zipkinSpan.setLocalEndpointServiceName(namingControl.formatServiceName(serviceName));
            if (span.localEndpoint() != null) {
                zipkinSpan.setLocalEndpointIPV4(span.localEndpoint().ipv4());
                zipkinSpan.setLocalEndpointIPV6(span.localEndpoint().ipv6());
                Integer localPort = span.localEndpoint().port();
                if (localPort != null) {
                    zipkinSpan.setLocalEndpointPort(localPort);
                }
            }
            if (span.remoteEndpoint() != null) {
                zipkinSpan.setRemoteEndpointServiceName(namingControl.formatServiceName(span.remoteServiceName()));
                zipkinSpan.setRemoteEndpointIPV4(span.remoteEndpoint().ipv4());
                zipkinSpan.setRemoteEndpointIPV6(span.remoteEndpoint().ipv6());
                Integer remotePort = span.remoteEndpoint().port();
                if (remotePort != null) {
                    zipkinSpan.setRemoteEndpointPort(remotePort);
                }
            }
            zipkinSpan.setTimestamp(span.timestampAsLong());
            zipkinSpan.setDebug(span.debug());
            zipkinSpan.setShared(span.shared());

            long timestampMillis = span.timestampAsLong() / 1000;
            zipkinSpan.setTimestampMillis(timestampMillis);
            long timeBucket = TimeBucket.getRecordTimeBucket(timestampMillis);
            zipkinSpan.setTimeBucket(timeBucket);

            long minuteTimeBucket = TimeBucket.getMinuteTimeBucket(timestampMillis);

            if (!span.tags().isEmpty() || !span.annotations().isEmpty()) {
                List<String> query = zipkinSpan.getQuery();
                JsonObject annotationsJson = new JsonObject();
                JsonObject tagsJson = new JsonObject();
                for (Annotation annotation : span.annotations()) {
                    annotationsJson.addProperty(Long.toString(annotation.timestamp()), annotation.value());
                    if (annotation.value().length() > ZipkinSpanRecord.QUERY_LENGTH) {
                        if (log.isDebugEnabled()) {
                            log.debug("Span annotation : {}  length > : {}, dropped", annotation.value(), ZipkinSpanRecord.QUERY_LENGTH);
                        }
                        continue;
                    }
                    query.add(annotation.value());
                }
                zipkinSpan.setAnnotations(annotationsJson);
                for (Map.Entry<String, String> tag : span.tags().entrySet()) {
                    String tagString = tag.getKey() + "=" + tag.getValue();
                    tagsJson.addProperty(tag.getKey(), tag.getValue());
                    if (tag.getValue().length()  > Tag.TAG_LENGTH || tagString.length() > Tag.TAG_LENGTH) {
                        if (log.isDebugEnabled()) {
                            log.debug("Span tag : {} length > : {}, dropped", tagString, Tag.TAG_LENGTH);
                        }
                        continue;
                    }
                    query.add(tag.getKey());
                    query.add(tagString);

                    if (searchTagKeys.contains(tag.getKey())) {
                        addAutocompleteTags(minuteTimeBucket, tag.getKey(), tag.getValue());
                    }
                }
                zipkinSpan.setTags(tagsJson);
            }
            receiver.receive(zipkinSpan);

            toService(zipkinSpan, minuteTimeBucket);
            toServiceSpan(zipkinSpan, minuteTimeBucket);
            if (!StringUtil.isEmpty(zipkinSpan.getRemoteEndpointServiceName())) {
                toServiceRelation(zipkinSpan, minuteTimeBucket);
            }
        });
    }

    private void addAutocompleteTags(final long minuteTimeBucket, final String key, final String value) {
        TagAutocomplete tagAutocomplete = new TagAutocomplete();
        tagAutocomplete.setTagKey(key);
        tagAutocomplete.setTagValue(value);
        tagAutocomplete.setTagType(TagType.ZIPKIN);
        tagAutocomplete.setTimeBucket(minuteTimeBucket);
        receiver.receive(tagAutocomplete);
    }

    private void toService(ZipkinSpan zipkinSpan, final long minuteTimeBucket) {
        ZipkinService service = new ZipkinService();
        service.setServiceName(zipkinSpan.getLocalEndpointServiceName());
        service.setTimeBucket(minuteTimeBucket);
        receiver.receive(service);
    }

    private void toServiceSpan(ZipkinSpan zipkinSpan, final long minuteTimeBucket) {
        ZipkinServiceSpan serviceSpan = new ZipkinServiceSpan();
        serviceSpan.setServiceName(zipkinSpan.getLocalEndpointServiceName());
        serviceSpan.setSpanName(zipkinSpan.getName());
        serviceSpan.setTimeBucket(minuteTimeBucket);
        receiver.receive(serviceSpan);
    }

    private void toServiceRelation(ZipkinSpan zipkinSpan, final long minuteTimeBucket) {
        ZipkinServiceRelation relation = new ZipkinServiceRelation();
        relation.setServiceName(zipkinSpan.getLocalEndpointServiceName());
        relation.setRemoteServiceName(zipkinSpan.getRemoteEndpointServiceName());
        relation.setTimeBucket(minuteTimeBucket);
        receiver.receive(relation);
    }

    private List<Span> getSampledTraces(List<Span> input) {
        //100% sampleRate
        if (config.getSampleRate() == 10000) {
            return input;
        }
        List<Span> sampledTraces = new ArrayList<>(input.size());
        for (Span span : input) {
            if (Boolean.TRUE.equals(span.debug())) {
                sampledTraces.add(span);
                continue;
            }
            long traceId = HexCodec.lowerHexToUnsignedLong(span.traceId());
            traceId = traceId == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(traceId);
            if (traceId <= samplerBoundary) {
                sampledTraces.add(span);
            }
        }
        return sampledTraces;
    }
}
