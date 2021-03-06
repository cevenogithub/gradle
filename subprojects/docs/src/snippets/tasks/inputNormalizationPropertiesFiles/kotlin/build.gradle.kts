plugins {
    java
}

// tag::ignore-property-selected[]
normalization {
    runtimeClasspath {
        properties("**/build-info.properties") {
            ignoreProperty("timestamp")
        }
    }
}
// end::ignore-property-selected[]

// tag::ignore-property-all[]
normalization {
    runtimeClasspath {
        properties {
            ignoreProperty("timestamp")
        }
    }
}
// end::ignore-property-all[]
