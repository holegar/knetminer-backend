export KNET_DATASET_TARGET="$KNET_DATA_TARGET/$KNET_DATASET_ID/$KNET_DATASET_VERSION"
[[ "$KNET_DATASET_HAS_NEO4J" == 'false' ]] || export KNET_DATASET_HAS_NEO4J='true'