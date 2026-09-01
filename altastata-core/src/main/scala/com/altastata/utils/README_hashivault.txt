
(Tools)$ ./vault server -dev

In the other terminal
---------------------

(Tools)$ export VAULT_ADDR='http://127.0.0.1:8200'

Use approle (https://www.vaultproject.io/docs/auth/approle.html):
=================================================================

(Tools)$ ./vault auth-enable approle
Successfully enabled 'approle' at 'approle'!

(Tools)$ ./vault write auth/approle/role/catrina777 secret_id_ttl=100m token_ttl=200m token_max_ttl=300m secret_id_num_uses=40
Success! Data written to: auth/approle/role/catrina777

(Tools)$ ./vault read auth/approle/role/catrina777/role-id
Key    	Value
---    	-----
role_id	57b2f7a2-aea9-05a3-e68d-96fd10cdc37f

(Tools)$ ./vault write -f auth/approle/role/catrina777/secret-id
Key               	Value
---               	-----
secret_id         	d29f1d2a-29c7-870b-bf02-f6eef3e6b246
secret_id_accessor	db9dcee0-4af4-9195-edc4-40612ffee7b8

(Tools)$ ./vault write auth/approle/login role_id=57b2f7a2-aea9-05a3-e68d-96fd10cdc37f secret_id=d29f1d2a-29c7-870b-bf02-f6eef3e6b246
Key            	Value
---            	-----
token          	<root-token-from-vault-dev>
token_accessor 	235b77fd-7df4-a1ba-be05-2a35ffedfe29
token_duration 	3h20m0s
token_renewable	true
token_policies 	[default]

Use cubbyhole (https://sreeninet.wordpress.com/2016/10/01/vault-use-cases/):
============================================================================

(Tools)$ ./vault auth <root-token-from-vault-dev>
Successfully authenticated! You are now logged in.
token: <root-token-from-vault-dev>
token_duration: 11979
token_policies: [default]

(Tools)$ ./vault write cubbyhole/catrina777 passwordkey=123
Success! Data written to: cubbyhole/catrina777

(Tools)$ ./vault read cubbyhole/catrina777
Key        	Value
---        	-----
passwordkey	123

