MATCH (n:PendingCall {project: $project})
WHERE n.calleeOwnerFqn = ''
RETURN count(n) AS value
