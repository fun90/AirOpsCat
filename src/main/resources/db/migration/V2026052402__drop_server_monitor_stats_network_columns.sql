ALTER TABLE server_monitor_stats
    DROP COLUMN IF EXISTS network_rx_bytes,
    DROP COLUMN IF EXISTS network_tx_bytes,
    DROP COLUMN IF EXISTS network_rx_increment_bytes,
    DROP COLUMN IF EXISTS network_tx_increment_bytes;
