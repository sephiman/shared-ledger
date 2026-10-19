package com.sephilabs.sharedledger.config

import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class JsonConfig {

    /** Null and omitted both mean "not stating it", so a request DTO's Kotlin default applies either way. SKIP rather
     *  than KotlinFeature.NullIsSameAsDefault: Jackson 3 rejects a null `boolean` before the Kotlin module sees it. */
    @Bean
    fun nullMeansAbsent() = JsonMapperBuilderCustomizer { builder ->
        builder.changeDefaultNullHandling { JsonSetter.Value.forValueNulls(Nulls.SKIP) }
    }
}
