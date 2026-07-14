UPDATE system_config
SET config_value = '30',
    update_time = CURRENT_TIMESTAMP
WHERE config_key = 'airopscat.account.guard.ttl-seconds'
  AND config_value = '15';
