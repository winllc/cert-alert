package com.winllc.certalert.config;

import org.springframework.data.jpa.datatables.repository.DataTablesRepositoryFactoryBean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Swaps in the DataTables repository factory so repositories extending
 * {@code DataTablesRepository} gain their {@code findAll(DataTablesInput, ...)} methods.
 */
@org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
@EnableJpaRepositories(
        basePackages = "com.winllc.certalert.repository",
        repositoryFactoryBeanClass = DataTablesRepositoryFactoryBean.class)
public class DataTablesConfig {}
