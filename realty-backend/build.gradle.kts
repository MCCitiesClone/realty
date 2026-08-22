plugins {
    `realty-conventions`
    `realty-publish`
}

dependencies {
    api(project(":realty-backend-api"))
    compileOnly("org.jetbrains:annotations:26.0.2-1")
    // Only for the annotations on DatabaseSettings; compileOnly because the shaded
    // plugin supplies (and relocates) the framework at runtime.
    compileOnly("io.paradaux:hibernia-framework:1.2.0-realty-SNAPSHOT")
    api("org.mybatis:mybatis:3.5.19")
    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.6")

    testImplementation("org.testcontainers:testcontainers-mariadb:2.0.1")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter:2.0.2")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name.set("Realty Backend")
                description.set("Core database and domain logic for Realty")
            }
        }
    }
}
