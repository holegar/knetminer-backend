set -e
cd "$KNET_SCRIPTS_HOME"
. config/init-dataset-cfg.sh

echo -e "\n\n\tSynchronising file dumps with their web publishing location\n"
echo -e "Please, beware, logs from this command report hashed dirs\n"

web_target="$KNET_DOWNLOAD_SSH:$KNET_DOWNLOAD_DIR/$KNET_DATASET_ID/$KNET_DATASET_VERSION"
secret_path="$KNET_WEB_SECRETS_DIR/$KNET_DATASET_ID-$KNET_DATASET_VERSION.key"

if [[ -e "$secret_path" ]]; then
	secret=`cat "$secret_path"`
	web_target="$web_target/$secret"
fi

chmod -R ugo+rX "$KNET_DATASET_TARGET"
rsync --exclude=tmp --exclude='.*' $RSYNC_DFLT_OPTS $RSYNC_MIRROR_OPTS "$KNET_DATASET_TARGET/" "$web_target"


# The Neo4j stuff
#

if [[ "$KNET_DATASET_HAS_NEO4J" != 'false' ]]; then 
	echo -e "\n\n\tSynchronising file dump with Neo4j server\n"
	rsout=`rsync $RSYNC_DFLT_OPTS $RSYNC_BKP_OPTS "$KNET_DATASET_TARGET/neo4j.dump" \
	             "$KNET_NEO_SERVER_SSH:$KNET_NEO_SERVER_DATA_DIR/$KNET_DATASET_ID-$KNET_DATASET_VERSION-neo4j.dump" | tee /dev/tty`
	    
	# Synch Neo4j only if the dump was actually updated         
	if [[ ! "$rsout" =~ 'Number of regular files transferred: 0' ]]; then 
	
	  echo -e "\n\n\tRe-populating the Neo4j Server\n"
	  ./config/neo4j/neo-server-update-$KNET_DATASET_ID.sh
	
	fi
fi

if [[ ! -z "$KNET_TEST_SERVERS" ]]; then
	echo -e "\n\n\tSynchronising OXL data on KnetMiner app servers (requires manual reload)\n"
	for host in $KNET_TEST_SERVERS
	do
	  echo -e "\nSynchronising OXL dump with '$host' Knetminer server\n"
	  rsout=`rsync $RSYNC_DFLT_OPTS $RSYNC_BKP_OPTS \
	        "$KNET_DATASET_TARGET/knowledge-graph-uris.oxl" \
	        "$KNET_SSH_USER@$host:$KNET_TEST_DATA_DIR/knowledge-network.oxl" | tee /dev/tty`
	
	  [[ "$rsout" =~ 'Number of regular files transferred: 0' ]] && continue
	
	  ./utils/knet-server-restart.sh $host
	
	done
fi
