#!/bin/sh

WORKROOT=/media/stelf/work2

docker container start friendly_khorana
tmux new-session -d -c $WORKROOT/web2print -n pechatar -s pechatar
tmux split-window -d -v -t pechatar -c $WORKROOT/visionr2018/visionr-dev 
tmux send-keys -t pechatar "vrs run" Enter
tmux send-keys -t pechatar.1 "npm start" Enter
# tmux new-window -n visionr-dev -t pechatar -n shell
# tmux rename-window -t pechatar:1 "visionr-dev"


# tmux rename-window -t pechatar:1 "server"
#tmux send-keys -t pechatar:1.2 "vrs run" Enter

tmux attach-session -d -t pechatar

