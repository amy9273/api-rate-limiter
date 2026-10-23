-- ==============================================================================
-- High-Throughput wrk Load Generator Script
-- Rotates client IP and API keys across requests to stress multi-bucket registry
-- ==============================================================================

local client_counter = 0

request = function()
    client_counter = (client_counter + 1) % 1000
    local api_key = "sk_wrk_tenant_" .. client_counter
    local client_ip = "10.0." .. math.floor(client_counter / 256) .. "." .. (client_counter % 256)

    wrk.headers["X-API-Key"] = api_key
    wrk.headers["X-Forwarded-For"] = client_ip
    wrk.headers["Accept"] = "application/json"

    return wrk.format("GET", "/v1/charges")
end

response = function(status, headers, body)
    if status ~= 200 and status ~= 429 then
        print("Unexpected HTTP status received: " .. status)
    end
end
