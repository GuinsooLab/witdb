module.exports = {
    entry: {
        'index': __dirname +'/index.jsx',
        'query': __dirname +'/query.jsx',
        'plan': __dirname +'/plan.jsx',
        'embedded_plan': __dirname +'/embedded_plan.jsx',
        'stage': __dirname +'/stage.jsx',
        'worker': __dirname +'/worker.jsx',
        'workers': __dirname +'/workers.jsx',
        'timeline': __dirname +'/timeline.jsx',
    },
    mode: "development",
    module: {
        rules: [
            {
                test: /\.(js|jsx)$/,
                exclude: /node_modules/,
                use: ['babel-loader']
            }
        ]
    },
    resolve: {
        extensions: ['*', '.js', '.jsx']
    },
    output: {
        path: __dirname + '/../dist',
        filename: '[name].js'
    }
};
