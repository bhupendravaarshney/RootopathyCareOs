local time = redis.call('TIME')
return tonumber(time[1])
