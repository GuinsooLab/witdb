#!/bin/bash
set -exuo pipefail

echo "Applying hive-site configuration overrides for Spark"
apply-site-xml-override /etc/hive/conf/hive-site.xml "/docker/presto-product-tests/conf/environment/singlenode-spark-iceberg-jdbc-catalog/hive-site-overrides.xml"
