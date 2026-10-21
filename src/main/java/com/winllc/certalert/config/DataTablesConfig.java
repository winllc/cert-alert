package com.winllc.certalert.config;

import org.springframework.data.jpa.datatables.repository.DataTablesRepositoryFactoryBean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Swaps in the DataTables repository factory so repositories extending
 * {@code DataTablesRepository} gain their {@code findAll(DataTablesInput, ...)} methods.
 * Every package holding a repository is named here, since naming any turns off Spring Boot's
 * own scan.
 */
@org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
@EnableJpaRepositories(
        basePackages = {
            "com.winllc.certalert.repository",
            "com.winllc.certalert.api",
            "com.winllc.certalert.webhook",
            "com.winllc.certalert.inventory",
            "com.winllc.certalert.export"
        },
        repositoryFactoryBeanClass = DataTablesRepositoryFactoryBean.class)
public class DataTablesConfig {}
